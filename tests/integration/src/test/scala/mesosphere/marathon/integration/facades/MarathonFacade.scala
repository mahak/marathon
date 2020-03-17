package mesosphere.marathon
package integration.facades

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding.{Delete, Get, Patch, Post, Put}
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.model.{MediaType, _}
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling
import akka.http.scaladsl.unmarshalling.{Unmarshal => AkkaUnmarshal}
import akka.stream.Materializer
import akka.stream.scaladsl.Source
import akka.util.ByteString
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import com.fasterxml.jackson.module.scala.experimental.ScalaObjectMapper
import com.mesosphere.utils.http.RestResult
import com.typesafe.scalalogging.StrictLogging
import de.heikoseeberger.akkahttpplayjson.PlayJsonSupport
import mesosphere.marathon
import mesosphere.marathon.api.RestResource
import mesosphere.marathon.core.pod.PodDefinition
import mesosphere.marathon.integration.raml18.PodStatus18
import mesosphere.marathon.raml.{App, AppUpdate, GroupPartialUpdate, GroupUpdate, Pod, PodConversion, PodInstanceStatus, PodStatus, Raml}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.util.Retry
import play.api.libs.functional.syntax._
import play.api.libs.json.JsArray

import scala.collection.immutable.Seq
import scala.concurrent.Await.result
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/**
  * GET /apps will deliver something like Apps instead of List[App]
  * Needed for dumb jackson.
  */
case class ITAppDefinition(app: App)
case class ITListAppsResult(apps: Seq[App])
case class ITAppVersions(versions: Seq[Timestamp])
case class ITListTasks(tasks: Seq[ITEnrichedTask])
case class ITDeploymentPlan(version: String, deploymentId: String)
case class ITHealthCheckResult(firstSuccess: Option[String], lastSuccess: Option[String], lastFailure: Option[String], consecutiveFailures: Int, alive: Boolean)
case class ITCheckResult(http: Option[ITHttpCheckStatus] = None, tcp: Option[ITTCPCheckStatus] = None, command: Option[ITCommandCheckStatus] = None)
case class ITDeploymentResult(version: Timestamp, deploymentId: String)
case class ITHttpCheckStatus(statusCode: Int)
case class ITTCPCheckStatus(succeeded: Boolean)
case class ITCommandCheckStatus(exitCode: Int)
case class ITEnrichedTask(
    appId: String,
    id: String,
    host: String,
    ports: Option[Seq[Int]],
    slaveId: Option[String],
    startedAt: Option[Timestamp],
    stagedAt: Option[Timestamp],
    state: String,
    version: Option[String],
    region: Option[String],
    zone: Option[String],
    check: Option[ITCheckResult],
    role: Option[String],
    healthCheckResults: Seq[ITHealthCheckResult]) {

  def launched: Boolean = startedAt.nonEmpty
  def suspended: Boolean = startedAt.isEmpty
}
case class ITLeaderResult(leader: String) {
  val port: Integer = leader.split(":")(1).toInt
}

case class ITListDeployments(deployments: Seq[ITDeployment])

case class ITQueueDelay(timeLeftSeconds: Int, overdue: Boolean)
case class ITQueueItem(app: App, count: Int, delay: ITQueueDelay)
case class ITLaunchQueue(queue: List[ITQueueItem])

case class ITDeployment(id: String, affectedApps: Seq[String], affectedPods: Seq[String])

sealed trait ITSSEEvent
/** Used to signal that the SSE stream is connected */
case object ITConnected extends ITSSEEvent

/** models each SSE published event */
case class ITEvent(eventType: String, info: Map[String, Any]) extends ITSSEEvent

/**
  * The MarathonFacade offers the REST API of a remote marathon instance
  * with all local domain objects.
  *
  * @param url the url of the remote marathon instance
  */
class MarathonFacade(
    val url: String, baseGroup: AbsolutePathId, implicit val waitTime: FiniteDuration = 30.seconds)(
    implicit
    val system: ActorSystem, mat: Materializer)
  extends PodConversion with StrictLogging {
  implicit val scheduler = system.scheduler
  import com.mesosphere.utils.http.AkkaHttpResponse._

  import scala.concurrent.ExecutionContext.Implicits.global

  require(baseGroup.absolute)

  import PlayJsonSupport.marshaller
  import mesosphere.marathon.api.v2.json.Formats._
  import play.api.libs.json._

  implicit lazy val itAppDefinitionFormat = Json.format[ITAppDefinition]
  implicit lazy val itListAppsResultFormat = Json.format[ITListAppsResult]
  implicit lazy val itAppVersionsFormat = Json.format[ITAppVersions]
  implicit lazy val itListTasksFormat = Json.format[ITListTasks]
  implicit lazy val itDeploymentPlanFormat = Json.format[ITDeploymentPlan]
  implicit lazy val itHealthCheckResultFormat = Json.format[ITHealthCheckResult]
  implicit lazy val itDeploymentResultFormat = Json.format[ITDeploymentResult]
  implicit lazy val itLeaderResultFormat = Json.format[ITLeaderResult]
  implicit lazy val itDeploymentFormat = Json.format[ITDeployment]
  implicit lazy val itListDeploymentsFormat = Json.format[ITListDeployments]
  implicit lazy val itQueueDelayFormat = Json.format[ITQueueDelay]
  implicit lazy val itQueueItemFormat = Json.format[ITQueueItem]
  implicit lazy val itLaunchQueueFormat = Json.format[ITLaunchQueue]

  implicit lazy val itHttpCheckStatus = Json.format[ITHttpCheckStatus]
  implicit lazy val itTCPCheckStatus = Json.format[ITTCPCheckStatus]
  implicit lazy val itCommandCheckStatus = Json.format[ITCommandCheckStatus]
  implicit lazy val itCheckResultFormat: Format[ITCheckResult] = (
    (__ \ "http").formatNullable[ITHttpCheckStatus] ~
    (__ \ "tcp").formatNullable[ITTCPCheckStatus] ~
    (__ \ "command").formatNullable[ITCommandCheckStatus]
  )(ITCheckResult(_, _, _), unlift(ITCheckResult.unapply))

  implicit lazy val itEnrichedTaskFormat: Format[ITEnrichedTask] = (
    (__ \ "appId").format[String] ~
    (__ \ "id").format[String] ~
    (__ \ "host").format[String] ~
    (__ \ "ports").formatNullable[Seq[Int]] ~
    (__ \ "slaveId").formatNullable[String] ~
    (__ \ "startedAt").formatNullable[Timestamp] ~
    (__ \ "stagedAt").formatNullable[Timestamp] ~
    (__ \ "state").format[String] ~
    (__ \ "version").formatNullable[String] ~
    (__ \ "region").formatNullable[String] ~
    (__ \ "zone").formatNullable[String] ~
    (__ \ "checkResult").formatNullable[ITCheckResult] ~
    (__ \ "role").formatNullable[String] ~
    (__ \ "healthCheckResults").formatWithDefault[Seq[ITHealthCheckResult]](Nil)
  )(ITEnrichedTask, unlift(ITEnrichedTask.unapply))

  def isInBaseGroup(pathId: PathId): Boolean = {
    pathId.path.startsWith(baseGroup.path)
  }

  def requireInBaseGroup(pathId: PathId): Unit = {
    require(isInBaseGroup(pathId), s"pathId $pathId must be in baseGroup ($baseGroup)")
  }

  // we don't want to lose any events and the default maxEventSize is too small (8K)
  object EventUnmarshalling extends EventStreamUnmarshalling {
    override protected def maxEventSize: Int = Int.MaxValue
    override protected def maxLineSize: Int = Int.MaxValue
  }

  /**
    * Connects to the Marathon SSE endpoint. Future completes when the http connection is established. Events are
    * streamed via the materializable-once Source.
    */
  def events(eventsType: Seq[String] = Seq.empty): Future[Source[ITEvent, NotUsed]] = {
    import EventUnmarshalling._
    val mapper = new ObjectMapper() with ScalaObjectMapper
    mapper.registerModule(DefaultScalaModule)

    val eventsFilter = Query(eventsType.map(eventType => "event_type" -> eventType): _*)

    Http().singleRequest(Get(akka.http.scaladsl.model.Uri(s"$url/v2/events").withQuery(eventsFilter))
      .withHeaders(Accept(MediaType.text("event-stream"))))
      .flatMap { response =>
        logger.info(s"Unmarshall SSE response from $url")
        AkkaUnmarshal(response).to[Source[ServerSentEvent, NotUsed]]
      }
      .map { stream =>
        logger.info(s"Mapping SSE stream from $url")
        stream
          .map { event =>
            logger.info(s"Parsing JSON from $url")
            val json = mapper.readValue[Map[String, Any]](event.data) // linter:ignore
            ITEvent(event.eventType.getOrElse("unknown"), json)
          }
      }
  }

  //app resource ----------------------------------------------

  def listAppsInBaseGroup: RestResult[List[App]] = {
    val res = result(requestFor[ITListAppsResult](Get(s"$url/v2/apps")), waitTime)
    res.map(_.apps.filterAs(app => isInBaseGroup(app.id.toPath))(collection.breakOut))
  }

  def listAppsInBaseGroupForAppId(appId: AbsolutePathId): RestResult[List[App]] = {
    val res = result(requestFor[ITListAppsResult](Get(s"$url/v2/apps")), waitTime)
    res.map(_.apps.filterAs(app => isInBaseGroup(app.id.toPath) && app.id.toPath == appId)(collection.breakOut))
  }

  def app(id: AbsolutePathId): RestResult[ITAppDefinition] = {
    requireInBaseGroup(id)
    val getUrl: String = s"$url/v2/apps$id"
    logger.info(s"get url = $getUrl")
    result(requestFor[ITAppDefinition](Get(getUrl)), waitTime)
  }

  def createAppV2(app: App): RestResult[App] = {
    requireInBaseGroup(app.id.toPath)
    result(requestFor[App](Post(s"$url/v2/apps", app)), waitTime)
  }

  def deleteApp(id: AbsolutePathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    result(requestFor[ITDeploymentResult](Delete(s"$url/v2/apps$id?force=$force")), waitTime)
  }

  def updateApp(id: AbsolutePathId, app: AppUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val putUrl: String = s"$url/v2/apps$id?force=$force"
    logger.info(s"put url = $putUrl")

    result(requestFor[ITDeploymentResult](Put(putUrl, app)), waitTime)
  }

  def putAppByteString(id: AbsolutePathId, app: ByteString): RestResult[ITDeploymentResult] = {
    val putUrl: String = s"$url/v2/apps$id"
    logger.info(s"put url = $putUrl")
    result(requestFor[ITDeploymentResult](Put(putUrl, HttpEntity.Strict(ContentTypes.`application/json`, app))), waitTime)
  }

  def patchApp(id: AbsolutePathId, app: AppUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    val putUrl: String = s"$url/v2/apps$id?force=$force"
    logger.info(s"put url = $putUrl")

    result(requestFor[ITDeploymentResult](Patch(putUrl, app)), waitTime)
  }

  def restartApp(id: AbsolutePathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    result(requestFor[ITDeploymentResult](Post(s"$url/v2/apps$id/restart?force=$force")), waitTime)
  }

  def listAppVersions(id: AbsolutePathId): RestResult[ITAppVersions] = {
    requireInBaseGroup(id)
    result(requestFor[ITAppVersions](Get(s"$url/v2/apps$id/versions")), waitTime)
  }

  def appVersion(id: AbsolutePathId, version: Timestamp): RestResult[App] = {
    requireInBaseGroup(id)
    result(requestFor[App](Get(s"$url/v2/apps$id/versions/$version")), waitTime)
  }

  //pod resource ---------------------------------------------

  def listPodsInBaseGroup: RestResult[Seq[PodDefinition]] = {
    val res = result(requestFor[Seq[Pod]](Get(s"$url/v2/pods")), waitTime)
    res.map(_.map(Raml.fromRaml(_))).map(_.filter(pod => isInBaseGroup(pod.id)))
  }

  def listPodsInBaseGroupByPodId(podId: AbsolutePathId): RestResult[Seq[PodDefinition]] = {
    val res = result(requestFor[Seq[Pod]](Get(s"$url/v2/pods")), waitTime)
    res.map(_.map(Raml.fromRaml(_))).map(_.filter(_.id == podId))
  }

  def pod(id: AbsolutePathId): RestResult[PodDefinition] = {
    requireInBaseGroup(id)
    val res = result(requestFor[Pod](Get(s"$url/v2/pods$id")), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def podTasksIds(podId: AbsolutePathId): Seq[String] = status(podId).value.instances.flatMap(_.containers.flatMap(_.containerId))

  def createPodV2(pod: PodDefinition): RestResult[PodDefinition] = {
    requireInBaseGroup(pod.id)
    val res = result(requestFor[Pod](Post(s"$url/v2/pods", Raml.toRaml(pod))), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def deletePod(id: AbsolutePathId, force: Boolean = false): RestResult[HttpResponse] = {
    requireInBaseGroup(id)
    result(request(Delete(s"$url/v2/pods$id?force=$force")), waitTime)
  }

  def updatePod(id: AbsolutePathId, pod: PodDefinition, force: Boolean = false): RestResult[PodDefinition] = {
    requireInBaseGroup(id)
    val res = result(requestFor[Pod](Put(s"$url/v2/pods$id?force=$force", Raml.toRaml(pod))), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def status(podId: AbsolutePathId): RestResult[PodStatus] = {
    requireInBaseGroup(podId)
    result(requestFor[PodStatus](Get(s"$url/v2/pods$podId::status")), waitTime)
  }

  /**
    * ================================= NOTE =================================
    * This is a copy of [[status()]] method which uses [[PodStatus18]] class that doesn't have `role` field for the pod
    * instances. This is ONLY used in [[mesosphere.marathon.integration.UpgradeIntegrationTest]] where we query old
    * Marathon instances.
    * ========================================================================
    */
  def status18(podId: PathId): RestResult[PodStatus18] = {
    requireInBaseGroup(podId)
    result(requestFor[PodStatus18](Get(s"$url/v2/pods$podId::status")), waitTime)
  }

  def listPodVersions(podId: AbsolutePathId): RestResult[Seq[Timestamp]] = {
    requireInBaseGroup(podId)
    result(requestFor[Seq[Timestamp]](Get(s"$url/v2/pods$podId::versions")), waitTime)
  }

  def podVersion(podId: AbsolutePathId, version: Timestamp): RestResult[PodDefinition] = {
    requireInBaseGroup(podId)
    val res = result(requestFor[Pod](Get(s"$url/v2/pods$podId::versions/$version")), waitTime)
    res.map(Raml.fromRaml(_))
  }

  def deleteAllInstances(podId: AbsolutePathId): RestResult[List[PodInstanceStatus]] = {
    requireInBaseGroup(podId)
    result(requestFor[List[PodInstanceStatus]](Delete(s"$url/v2/pods$podId::instances")), waitTime)
  }

  def deleteInstance(podId: AbsolutePathId, instance: String, wipe: Boolean = false): RestResult[PodInstanceStatus] = {
    requireInBaseGroup(podId)
    result(requestFor[PodInstanceStatus](Delete(s"$url/v2/pods$podId::instances/$instance?wipe=$wipe")), waitTime)
  }

  //apps tasks resource --------------------------------------
  def tasks(appId: AbsolutePathId): RestResult[List[ITEnrichedTask]] = {
    requireInBaseGroup(appId)
    val res = result(requestFor[ITListTasks](Get(s"$url/v2/apps$appId/tasks")), waitTime)
    res.map(_.tasks.toList)
  }

  def killAllTasks(appId: AbsolutePathId, scale: Boolean = false): RestResult[ITListTasks] = {
    requireInBaseGroup(appId)
    result(requestFor[ITListTasks](Delete(s"$url/v2/apps$appId/tasks?scale=$scale")), waitTime)
  }

  def killAllTasksAndScale(appId: AbsolutePathId): RestResult[ITDeploymentPlan] = {
    requireInBaseGroup(appId)
    result(requestFor[ITDeploymentPlan](Delete(s"$url/v2/apps$appId/tasks?scale=true")), waitTime)
  }

  def killTask(appId: AbsolutePathId, taskId: String, scale: Boolean = false, wipe: Boolean = false): RestResult[HttpResponse] = {
    requireInBaseGroup(appId)
    result(request(Delete(s"$url/v2/apps$appId/tasks/$taskId?scale=$scale&wipe=$wipe")), waitTime)
  }

  //group resource -------------------------------------------

  def listGroupIdsInBaseGroup: RestResult[Set[String]] = {
    // This actually returns GroupInfo, not GroupUpdate, but it maps mostly the same and we don't have a
    // deserializer for GroupInfo at the moment
    val root = result(requestFor[raml.GroupUpdate](Get(s"$url/v2/groups")), waitTime)

    root.map(_.groups.getOrElse(Set.empty).filter(subGroup => isInBaseGroup(PathId(subGroup.id.get))).map(_.id.get))
  }

  def listGroupVersions(id: AbsolutePathId): RestResult[List[String]] = {
    requireInBaseGroup(id)
    result(requestFor[List[String]](Get(s"$url/v2/groups$id/versions")), waitTime)
  }

  /**
    * This should actually return a raml.GroupInfo, but we dont' have deserialization for that. GroupUpdate is only missing the
    * pods, sooo, close enough
    */
  def group(id: AbsolutePathId): RestResult[raml.GroupUpdate] = {
    requireInBaseGroup(id)
    result(requestFor[raml.GroupUpdate](Get(s"$url/v2/groups$id")), waitTime)
  }

  def createGroup(group: GroupUpdate): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(group.id.map(PathId(_)).getOrElse(throw new IllegalArgumentException("missing group.id")))
    result(requestFor[ITDeploymentResult](Post(s"$url/v2/groups", group)), waitTime)
  }

  def deleteGroup(id: AbsolutePathId, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    result(requestFor[ITDeploymentResult](Delete(s"$url/v2/groups$id?force=$force")), waitTime)
  }

  def deleteRoot(force: Boolean): RestResult[ITDeploymentResult] = {
    result(requestFor[ITDeploymentResult](Delete(s"$url/v2/groups?force=$force")), waitTime)
  }

  def updateGroup(id: AbsolutePathId, group: GroupUpdate, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(id)
    result(requestFor[ITDeploymentResult](Put(s"$url/v2/groups$id?force=$force", group)), waitTime)
  }

  def patchGroup(id: AbsolutePathId, update: GroupPartialUpdate): RestResult[String] = {
    result(requestFor[String](Patch(s"$url/v2/groups$id", update)), waitTime)
  }

  def rollbackGroup(groupId: AbsolutePathId, version: Timestamp, force: Boolean = false): RestResult[ITDeploymentResult] = {
    requireInBaseGroup(groupId)
    updateGroup(groupId, GroupUpdate(None, version = Some(version.toOffsetDateTime)), force)
  }

  //deployment resource ------

  def listDeploymentsForBaseGroup(): RestResult[List[ITDeployment]] = {
    result(requestFor[List[ITDeployment]](Get(s"$url/v2/deployments")), waitTime).map { deployments =>
      deployments.filter { deployment =>
        deployment.affectedApps.map(PathId(_)).exists(id => isInBaseGroup(id)) ||
          deployment.affectedPods.map(PathId(_)).exists(id => isInBaseGroup(id))
      }
    }
  }

  def listDeploymentsForPathId(pathId: AbsolutePathId): RestResult[List[ITDeployment]] = {
    result(requestFor[List[ITDeployment]](Get(s"$url/v2/deployments")), waitTime).map { deployments =>
      deployments.filter { deployment =>
        deployment.affectedApps.map(PathId(_)).contains(pathId) ||
          deployment.affectedPods.map(PathId(_)).contains(pathId)
      }
    }
  }

  def deleteDeployment(id: String, force: Boolean = false): RestResult[HttpResponse] = {
    result(request(Delete(s"$url/v2/deployments/$id?force=$force")), waitTime)
  }

  //metrics ---------------------------------------------

  def metrics(): RestResult[HttpResponse] = {
    result(request(Get(s"$url/metrics")), waitTime)
  }

  def prometheusMetrics(): RestResult[HttpResponse] = {
    result(request(Get(s"$url/metrics/prometheus")), waitTime)
  }

  def ping(): RestResult[HttpResponse] = {
    result(request(Get(s"$url/ping")), waitTime)
  }

  //leader ----------------------------------------------
  def leader(): RestResult[ITLeaderResult] = {
    result(leaderAsync(), waitTime)
  }

  def leaderAsync(): Future[RestResult[ITLeaderResult]] = {
    Retry("leader") { requestFor[ITLeaderResult](Get(s"$url/v2/leader")) }
  }

  def abdicate(): RestResult[HttpResponse] = {
    result(Retry("abdicate") { request(Delete(s"$url/v2/leader")) }, waitTime)
  }

  def abdicateWithBackup(file: String): RestResult[HttpResponse] = {
    result(Retry("abdicate") { request(Delete(s"$url/v2/leader?backup=file://$file")) }, waitTime)
  }

  def abdicateWithRestore(file: String): RestResult[HttpResponse] = {
    result(Retry("abdicate") { request(Delete(s"$url/v2/leader?restore=file://$file")) }, waitTime)
  }

  //info --------------------------------------------------
  def info: RestResult[HttpResponse] = {
    result(request(Get(s"$url/v2/info")), waitTime)
  }

  //launch queue ------------------------------------------
  def launchQueue(): RestResult[ITLaunchQueue] = {
    result(requestFor[ITLaunchQueue](Get(s"$url/v2/queue")), waitTime)
  }

  def launchQueueForAppId(appId: AbsolutePathId): RestResult[List[ITQueueItem]] = {
    val res = result(requestFor[ITLaunchQueue](Get(s"$url/v2/queue")), waitTime)
    res.map(_.queue.filterAs(q => q.app.id.toPath == appId)(collection.breakOut))
  }

  def launchQueueDelayReset(appId: AbsolutePathId): RestResult[HttpResponse] =
    result(request(Delete(s"$url/v2/queue/$appId/delay")), waitTime)

  //resources -------------------------------------------

  def getPath(path: String): RestResult[HttpResponse] = {
    result(request(Get(s"$url$path")), waitTime)
  }
}

object MarathonFacade {
  def extractDeploymentIds(app: RestResult[App]): IndexedSeq[String] = {
    try {
      for (deployment <- (app.entityJson \ "deployments").as[JsArray].value)
        yield (deployment \ "id").as[String]
    } catch {
      case NonFatal(e) =>
        throw new RuntimeException(s"while parsing:\n${app.entityPrettyJsonString}", e)
    }
  }.to[marathon.IndexedSeq]

  /**
    * Enables easy access to a deployment ID in the header of an [[HttpResponse]] in a [[RestResult]].
    * @param response The result of an HTTP request.
    */
  implicit class DeploymentId(response: RestResult[_]) {
    /**
      * @return Deployment ID from headers, if any
      */
    def deploymentId: Option[String] =
      response.originalResponse.headers.collectFirst { case header if header.name == RestResource.DeploymentHeader => header.value }
  }
}
