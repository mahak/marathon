package mesosphere.marathon
package core.launcher.impl

import mesosphere.UnitTest
import mesosphere.marathon.core.instance.{Instance, Reservation}
import mesosphere.marathon.state.AbsolutePathId
import scala.jdk.CollectionConverters._
import mesosphere.marathon.test.MarathonTestHelper
import mesosphere.util.state.FrameworkId
import org.apache.mesos.{Protos => MesosProtos}

class TaskLabelsTest extends UnitTest {
  "TaskLabels" should {
    "no labels => no taskId" in {
      val f = new Fixture

      Given("unlabeled resources")
      When("checking for taskIds")
      val instanceIds = f.unlabeledResources.flatMap(TaskLabels.instanceIdForResource(f.frameworkId, _))

      Then("we don't get any instanceIds")
      instanceIds should be(empty)
    }

    "correct labels => taskId" in {
      val f = new Fixture

      Given("correctly labeled resources")
      When("checking for instanceIds")
      val instanceIds = f.labeledResources.flatMap(TaskLabels.instanceIdForResource(f.frameworkId, _))

      Then("we get as many instanceIds as resources")
      instanceIds should be(Seq.fill(f.labeledResources.size)(f.reservationId.instanceId))
    }

    "labels with incorrect frameworkId are ignored" in {
      val f = new Fixture

      Given("labeled resources for other framework")
      When("checking for instanceIds")
      val instanceIds = f.labeledResourcesForOtherFramework.flatMap(TaskLabels.instanceIdForResource(f.frameworkId, _))

      Then("we don't get instanceIds")
      instanceIds should be(empty)
    }
  }
  class Fixture {
    val appId = AbsolutePathId("/test")
    val instanceId = Instance.Id.forRunSpec(appId)
    val reservationId = Reservation.SimplifiedId(instanceId)
    val frameworkId = MarathonTestHelper.frameworkId
    val otherFrameworkId = FrameworkId("very other different framework id")

    val unlabeledResources = MarathonTestHelper.makeBasicOffer().getResourcesList.asScala
    require(unlabeledResources.nonEmpty)
    require(unlabeledResources.forall(!_.hasReservation))

    def labelResourcesFor(frameworkId: FrameworkId): Seq[MesosProtos.Resource] = {
      MarathonTestHelper.makeBasicOffer(
        reservation = Some(TaskLabels.labelsForTask(frameworkId, reservationId)),
        role = "test"
      ).getResourcesList.asScala.to(Seq)
    }

    val labeledResources = labelResourcesFor(frameworkId)
    require(labeledResources.nonEmpty)
    require(labeledResources.forall(_.hasReservation))

    val labeledResourcesForOtherFramework = labelResourcesFor(otherFrameworkId)
    require(labeledResourcesForOtherFramework.nonEmpty)
    require(labeledResourcesForOtherFramework.forall(_.hasReservation))
  }
}
