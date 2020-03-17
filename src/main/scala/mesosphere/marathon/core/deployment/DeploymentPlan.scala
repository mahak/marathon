package mesosphere.marathon
package core.deployment

import java.util.UUID

import com.wix.accord._
import com.wix.accord.dsl._
import mesosphere.marathon.api.v2.Validation._
import mesosphere.marathon.api.v2.validation.PodsValidation
import mesosphere.marathon.core.deployment.impl.DeploymentPlanReverter
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.pod.{MesosContainer, PodDefinition}
import mesosphere.marathon.core.readiness.ReadinessCheckResult
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.raml.{ArgvCommand, ShellCommand}
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state._

import scala.collection.SortedMap
import scala.jdk.CollectionConverters._

sealed trait DeploymentAction {
  def runSpec: RunSpec
}

object DeploymentAction {

  def actionName(action: DeploymentAction): String = {
    val actionType = action.runSpec match {
      case app: AppDefinition => "Application"
      case pod: PodDefinition => "Pod"
    }
    action match {
      case _: StartApplication => s"Start$actionType"
      case _: StopApplication => s"Stop$actionType"
      case _: ScaleApplication => s"Scale$actionType"
      case _: RestartApplication => s"Restart$actionType"
    }
  }
}

/**
  * This deployment step exists for backwards compatibility purposes only. It does effectively nothing and will be
  * immediately successful, see `startRunnable` method in [[mesosphere.marathon.core.deployment.impl.DeploymentActor]].
  */
case class StartApplication(runSpec: RunSpec) extends DeploymentAction {
  val scaleTo: Int = 0 //StartApplication deployment step *always* has scaleTo=0 parameter. Scaling is handled in the ScaleApplication step
}

// runnable spec is started, but the instance count should be changed
case class ScaleApplication(
    runSpec: RunSpec,
    scaleTo: Int,
    sentencedToDeath: Seq[Instance] = Seq.empty) extends DeploymentAction

// runnable spec is started, but shall be completely stopped
case class StopApplication(runSpec: RunSpec) extends DeploymentAction

// runnable spec is there but should be replaced
case class RestartApplication(runSpec: RunSpec) extends DeploymentAction

/**
  * One step in a deployment plan.
  * The contained actions may be executed in parallel.
  *
  * @param actions the actions of this step that maybe executed in parallel
  */
case class DeploymentStep(actions: Seq[DeploymentAction]) {
  def +(step: DeploymentStep): DeploymentStep = DeploymentStep(actions ++ step.actions)
  def nonEmpty(): Boolean = actions.nonEmpty
}

object DeploymentStep {
  /**
    * We need to have a placeholder step for a situation when deployment is saved but we did not start processing steps
    * In that point in time, user have to still be able to query /deployments endpoint and have that deployment visible in there
    */
  def initial = DeploymentStep(Seq.empty)
}

/**
  * Current state of the deployment. Has the deployment plan, current step information [[DeploymentStep]] with the
  * step index and the corresponding readiness checks results [[core.readiness.ReadinessCheckResult]] for the app instances.
  *
  * @param plan deployment plan
  * @param step current deployment step
  * @param stepIndex current deployment step index
  * @param readinessChecks a map with readiness check results for app instances
  */
case class DeploymentStepInfo(
    plan: DeploymentPlan,
    step: DeploymentStep,
    stepIndex: Int,
    readinessChecks: Map[Task.Id, ReadinessCheckResult] = Map.empty) {
  lazy val readinessChecksByApp: Map[AbsolutePathId, Seq[ReadinessCheckResult]] = {
    readinessChecks.values.groupBy(_.taskId.runSpecId).map { case (k, v) => k -> v.to(Seq) }.withDefaultValue(Seq.empty)
  }
}

/**
  * A deployment plan consists of the [[mesosphere.marathon.core.deployment.DeploymentStep]]s necessary to
  * change the group state from original to target.
  *
  * The steps are executed sequentially after each other. The actions within a
  * step maybe executed in parallel.
  *
  * See `mesosphere.marathon.upgrade.DeploymentPlan.appsGroupedByLongestPath` to
  * understand how we can guarantee that all dependencies for a step are fulfilled
  * by prior steps.
  */
case class DeploymentPlan(
    id: String, // This is a UUID, *not* a path in any way
    original: RootGroup,
    target: RootGroup,
    steps: Seq[DeploymentStep],
    version: Timestamp) {

  /**
    * Reverts this plan by applying the reverse changes to the given Group.
    */
  def revert(rootGroup: RootGroup): RootGroup = DeploymentPlanReverter.revert(original, target)(rootGroup)

  lazy val isEmpty: Boolean = steps.isEmpty

  lazy val nonEmpty: Boolean = !isEmpty

  lazy val affectedRunSpecs: Set[RunSpec] = steps.iterator.flatMap(_.actions.map(_.runSpec)).toSet

  /** @return all ids of apps which are referenced in any deployment actions */
  lazy val affectedRunSpecIds: Set[AbsolutePathId] = steps.iterator.flatMap(_.actions.map(_.runSpec.id)).toSet

  def affectedAppIds: Set[AbsolutePathId] = affectedRunSpecs.collect{ case app: AppDefinition => app.id }
  def affectedPodIds: Set[AbsolutePathId] = affectedRunSpecs.collect{ case pod: PodDefinition => pod.id }

  def isAffectedBy(other: DeploymentPlan): Boolean =
    // FIXME: check for group change conflicts?
    affectedRunSpecIds.intersect(other.affectedRunSpecIds).nonEmpty

  lazy val createdOrUpdatedApps: Seq[AppDefinition] = {
    target.transitiveApps.iterator.filter(app => affectedRunSpecIds(app.id)).toSeq
  }

  lazy val deletedApps: Seq[AbsolutePathId] = {
    original.transitiveAppIds.filter(appId => target.app(appId).isEmpty).toIndexedSeq
  }

  lazy val createdOrUpdatedPods: Seq[PodDefinition] = {
    target.transitivePods.filter(pod => affectedRunSpecIds(pod.id)).toIndexedSeq
  }

  lazy val deletedPods: Seq[AbsolutePathId] = {
    original.transitivePodIds.filter(podId => target.pod(podId).isEmpty).toIndexedSeq
  }

  override def toString: String = {
    def specString(spec: RunSpec): String = spec match {
      case app: AppDefinition => appString(app)
      case pod: PodDefinition => podString(pod)

    }
    def podString(pod: PodDefinition): String = {
      val containers = pod.containers.map(containerString).mkString(", ")
      s"""Pod(id="${pod.id}", containers=[$containers], role=${pod.role})"""
    }
    def containerString(container: MesosContainer): String = {
      val command = container.exec.map{
        _.command match {
          case ShellCommand(shell) => s""", cmd="$shell""""
          case ArgvCommand(args) => s""", args="${args.mkString(", ")}""""
        }
      }
      val image = container.image.fold("")(image => s""", image="$image"""")
      s"""Container(name="${container.name}$image$command}")"""
    }
    def appString(app: RunSpec): String = {
      val cmdString = app.cmd.fold("")(cmd => ", cmd=\"" + cmd + "\"")
      val argsString = app.args.map(args => ", args=\"" + args.mkString(" ") + "\"")
      val maybeDockerImage: Option[String] = app.container.collect { case c: Docker => c.image }
      val dockerImageString = maybeDockerImage.fold("")(image => ", image=\"" + image + "\"")

      s"App(${app.id}$dockerImageString$cmdString$argsString, role = ${app.role}))"
    }
    def actionString(a: DeploymentAction): String = a match {
      case StartApplication(spec) => s"Start(${specString(spec)}, instances=0)"
      case StopApplication(spec) => s"Stop(${specString(spec)})"
      case ScaleApplication(spec, scale, toKill) =>
        val killTasksString = if (toKill.isEmpty) "" else ", killTasks=" + toKill.map(_.instanceId.idString).mkString(",")
        s"Scale(${specString(spec)}, instances=$scale$killTasksString)"
      case RestartApplication(app) => s"Restart(${specString(app)})"
    }
    val stepString =
      if (steps.nonEmpty) {
        steps
          .map { _.actions.map(actionString).mkString("  * ", "\n  * ", "") }
          .zipWithIndex
          .map { case (stepsString, index) => s"step ${index + 1}:\n$stepsString" }
          .mkString("\n", "\n", "")
      } else " NO STEPS"
    s"DeploymentPlan id=$id,$version$stepString\n"
  }

  def targetIdsString = affectedRunSpecIds.mkString(", ")
}

object DeploymentPlan {

  def empty: DeploymentPlan =
    DeploymentPlan(UUID.randomUUID().toString, RootGroup.empty(), RootGroup.empty(), Nil, Timestamp.now())

  /**
    * Perform a "layered" topological sort of all of the run specs that are going to be deployed.
    * The "layered" aspect groups the run specs that have the same length of dependencies for parallel deployment.
    */
  private[deployment] def runSpecsGroupedByLongestPath(
    affectedRunSpecIds: Set[AbsolutePathId],
    rootGroup: RootGroup): SortedMap[Int, Iterable[RunSpec]] = {

    import org.jgrapht.DirectedGraph
    import org.jgrapht.graph.DefaultEdge

    def longestPathFromVertex[V](g: DirectedGraph[V, DefaultEdge], vertex: V): Seq[V] = {

      val outgoingEdges: Set[DefaultEdge] =
        if (g.containsVertex(vertex)) g.outgoingEdgesOf(vertex).asScala.toSet
        else Set.empty[DefaultEdge]

      if (outgoingEdges.isEmpty)
        Seq(vertex)

      else
        outgoingEdges.map { e =>
          vertex +: longestPathFromVertex(g, g.getEdgeTarget(e))
        }.maxBy(_.length)

    }

    val unsortedEquivalenceClasses = rootGroup.transitiveRunSpecs.filter(spec => affectedRunSpecIds.contains(spec.id)).groupBy { runSpec =>
      longestPathFromVertex(rootGroup.dependencyGraph, runSpec).length
    }

    SortedMap(unsortedEquivalenceClasses.toSeq: _*)
  }

  /**
    * Returns a sequence of deployment steps, the order of which is derived
    * from the topology of the target group's dependency graph.
    */
  def dependencyOrderedSteps(original: RootGroup, target: RootGroup, affectedIds: Set[AbsolutePathId],
    toKill: Map[AbsolutePathId, Seq[Instance]]): Seq[DeploymentStep] = {

    val runsByLongestPath: SortedMap[Int, Iterable[RunSpec]] = runSpecsGroupedByLongestPath(affectedIds, target)

    runsByLongestPath.values.iterator.map { equivalenceClass: Iterable[RunSpec] =>
      val actions: Iterable[DeploymentAction] = equivalenceClass.flatMap { newSpec: RunSpec =>
        original.runSpec(newSpec.id) match {
          // New run spec.
          case None =>
            Some(ScaleApplication(newSpec, newSpec.instances))

          // Scale-only change.
          case Some(oldSpec) if oldSpec.isOnlyScaleChange(newSpec) || newSpec.isScaledToZero =>
            Some(ScaleApplication(newSpec, newSpec.instances, toKill.getOrElse(newSpec.id, Seq.empty)))

          // Update or restart an existing run spec.
          case Some(oldSpec) if oldSpec.needsRestart(newSpec) =>
            Some(RestartApplication(newSpec))

          // Other cases require no action.
          case _ =>
            None
        }
      }

      DeploymentStep(actions.to(Seq))
    }.toSeq
  }

  /**
    * @param original the root group before the deployment
    * @param target the root group after the deployment
    * @param version the version to use for new RunSpec (should be very close to now)
    * @param toKill specific tasks that should be killed
    * @return The deployment plan containing the steps necessary to get from the original to the target group definition
    */
  def apply(
    original: RootGroup,
    target: RootGroup,
    version: Timestamp = Timestamp.now(),
    toKill: Map[AbsolutePathId, Seq[Instance]] = Map.empty,
    id: Option[String] = None): DeploymentPlan = {

    // A collection of deployment steps for this plan.
    val steps = Seq.newBuilder[DeploymentStep]

    // 1. Destroy run specs that do not exist in the target.
    steps += DeploymentStep(
      original.transitiveRunSpecs.filter(oldRun => !target.exists(oldRun.id)).iterator.map { oldRun =>
        StopApplication(oldRun)
      }.toSeq
    )

    // 2. Start run specs that do not exist in the original, requiring only 0
    //    instances.  These are scaled as needed in the dependency-ordered
    //    steps that follow.
    steps += DeploymentStep(
      target.transitiveRunSpecs.filter(run => !original.exists(run.id)).iterator.map { newRun =>
        StartApplication(newRun)
      }.toSeq
    )

    // applications that are either new or the specs are different should be considered for the dependency graph
    val addedOrChanged: Iterable[AbsolutePathId] = target.transitiveRunSpecs.collect {
      case (spec) if (!original.runSpec(spec.id).contains(spec)) =>
        // the above could be optimized/refined further by checking the version info. The tests are actually
        // really bad about structuring this correctly though, so for now, we just make sure that
        // the specs are different (or brand new)
        spec.id
    }
    val affectedApplications: Set[AbsolutePathId] = {
      val builder = Set.newBuilder[AbsolutePathId]
      builder ++= addedOrChanged
      builder ++= original.transitiveRunSpecIds.filter(id => !target.exists(id))
      builder.result()
    }

    // 3. For each runSpec in each dependency class,
    //
    //      A. If this runSpec is new, scale to the target number of instances.
    //
    //      B. If this is a scale change only, scale to the target number of
    //         instances.
    //
    //      C. Otherwise, if this is an runSpec update:
    //         i. Scale down to the target minimumHealthCapacity fraction of
    //            the old runSpec or the new runSpec, whichever is less.
    //         ii. Restart the runSpec, up to the new target number of instances.
    //
    steps ++= dependencyOrderedSteps(original, target, affectedApplications, toKill)

    // Build the result.
    val result = DeploymentPlan(
      id.getOrElse(UUID.randomUUID().toString),
      original,
      target,
      steps.result().filter(_.actions.nonEmpty),
      version
    )

    result
  }

  def deploymentPlanValidator(): Validator[DeploymentPlan] = {
    validator[DeploymentPlan] { plan =>
      plan.createdOrUpdatedApps as "app" is every(AppDefinition.updateIsValid(plan.original))
      plan.createdOrUpdatedPods as "pod" is every(PodsValidation.updateIsValid(plan.original))
    }
  }
}
