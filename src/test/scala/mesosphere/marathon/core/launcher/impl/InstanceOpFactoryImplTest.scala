package mesosphere.marathon
package core.launcher.impl

import java.time.Clock

import mesosphere.UnitTest
import mesosphere.marathon.core.condition.Condition
import mesosphere.marathon.core.instance.Instance
import mesosphere.marathon.core.instance.Instance.AgentInfo
import mesosphere.marathon.core.pod.{MesosContainer, PodDefinition}
import mesosphere.marathon.core.task.Task.{EphemeralTaskId, TaskIdWithIncarnation}
import mesosphere.marathon.core.task.state.NetworkInfo
import mesosphere.marathon.core.task.{Task, Tasks}
import mesosphere.marathon.raml.{Endpoint, Resources}
import mesosphere.marathon.state.AbsolutePathId
import mesosphere.marathon.test.SettableClock
import mesosphere.mesos.TaskGroupBuilder

import scala.collection.immutable.Seq

// TODO(karsten): Merge with the other InstanceOpFactoryImplTest or remove it.
class InstanceOpFactoryImplTest extends UnitTest {

  import InstanceOpFactoryImplTest._

  "InstanceOpFactoryImpl" should {
    "minimal ephemeralPodInstance" in {
      val pod = minimalPod
      val tc = TestCase(pod, agentInfo)
      implicit val clock = new SettableClock()
      val instance = Instance.scheduled(pod, tc.instanceId).provisioned(
        agentInfo, pod, Tasks.provisioned(tc.taskIDs, tc.networkInfos, pod.version, clock.now()), clock.now())
      check(tc, instance)
    }

    "ephemeralPodInstance with endpoint no host port" in {
      val pod = minimalPod.copy(containers = minimalPod.containers.map { ct =>
        ct.copy(endpoints = Seq(Endpoint(name = "web")))
      })
      val tc = TestCase(pod, agentInfo)
      implicit val clock = new SettableClock()
      val instance = Instance.scheduled(pod, tc.instanceId).provisioned(
        agentInfo, pod, Tasks.provisioned(tc.taskIDs, tc.networkInfos, pod.version, clock.now()), clock.now())
      check(tc, instance)
    }

    "ephemeralPodInstance with endpoint one host port" in {
      val pod = minimalPod.copy(containers = minimalPod.containers.map { ct =>
        ct.copy(endpoints = Seq(Endpoint(name = "web", hostPort = Some(80))))
      })
      val tc = TestCase(pod, agentInfo)
      implicit val clock = new SettableClock()
      val instance = Instance.scheduled(pod, tc.instanceId).provisioned(
        agentInfo, pod, Tasks.provisioned(tc.taskIDs, tc.networkInfos, pod.version, clock.now()), clock.now())
      check(tc, instance)
    }

    "ephemeralPodInstance with multiple endpoints, mixed host ports" in {
      val pod = minimalPod.copy(containers = minimalPod.containers.map { ct =>
        ct.copy(endpoints = Seq(
          Endpoint(name = "ep0", hostPort = Some(80)),
          Endpoint(name = "ep1"),
          Endpoint(name = "ep2", hostPort = Some(90)),
          Endpoint(name = "ep3")
        ))
      })
      val tc = TestCase(pod, agentInfo)
      implicit val clock = new SettableClock()
      val instance = Instance.scheduled(pod, tc.instanceId).provisioned(
        agentInfo, pod, Tasks.provisioned(tc.taskIDs, tc.networkInfos, pod.version, clock.now()), clock.now())
      check(tc, instance)
    }

    "ephemeralPodInstance with multiple containers, multiple endpoints, mixed host ports" in {
      val pod = minimalPod.copy(containers = Seq(
        MesosContainer(name = "ct0", resources = someRes, endpoints = Seq(Endpoint(name = "ep0"))),
        MesosContainer(name = "ct1", resources = someRes, endpoints = Seq(
          Endpoint(name = "ep1", hostPort = Some(1)),
          Endpoint(name = "ep2", hostPort = Some(2))
        )),
        MesosContainer(name = "ct2", resources = someRes, endpoints = Seq(Endpoint(name = "ep3", hostPort = Some(3))))
      ))
      val tc = TestCase(pod, agentInfo)
      implicit val clock = new SettableClock()
      val instance = Instance.scheduled(pod, tc.instanceId).provisioned(
        agentInfo, pod, Tasks.provisioned(tc.taskIDs, tc.networkInfos, pod.version, clock.now()), clock.now())
      check(tc, instance)
    }

    "ephemeralPodInstance with multiple containers, multiple endpoints, mixed allocated/unallocated host ports" in {
      val pod = minimalPod.copy(containers = Seq(
        MesosContainer(name = "ct0", resources = someRes, endpoints = Seq(Endpoint(name = "ep0"))),
        MesosContainer(name = "ct1", resources = someRes, endpoints = Seq(
          Endpoint(name = "ep1", hostPort = Some(1)),
          Endpoint(name = "ep2", hostPort = Some(0))
        )),
        MesosContainer(name = "ct2", resources = someRes, endpoints = Seq(Endpoint(name = "ep3", hostPort = Some(3))))
      ))
      val tc = TestCase(pod, agentInfo)
      implicit val clock = new SettableClock()
      val instance = Instance.scheduled(tc.pod, tc.instanceId).provisioned(
        agentInfo, pod, Tasks.provisioned(tc.taskIDs, tc.networkInfos, pod.version, clock.now()), clock.now())
      check(tc, instance)
    }
  }
  def check(tc: TestCase, instance: Instance)(implicit clock: Clock): Unit = {
    import tc._

    instance.instanceId should be(instanceId)
    instance.agentInfo.value should be(agentInfo)
    instance.tasksMap.size should be(pod.containers.size)
    instance.tasksMap.keys.toSeq should be(taskIDs)

    val mappedPorts: Seq[Int] = instance.tasksMap.values.view.flatMap(_.status.networkInfo.hostPorts).toIndexedSeq
    mappedPorts should be(hostPortsAllocatedFromOffer.flatten)

    // TODO(karsten): This is super similar to InstanceOpFactoryImpl.podTaskNetworkInfos.
    val expectedHostPortsPerCT: Map[String, Seq[Int]] = pod.containers.iterator.map { ct =>
      ct.name -> ct.endpoints.flatMap{ ep =>
        ep.hostPort match {
          case Some(hostPort) if hostPort == 0 => Some(fakeAllocatedPort)

          // isn't there a more compact way to represent the next two lines?
          case Some(hostPort) => Some(hostPort)
          case None => None
        }
      }
    }.toMap

    val allocatedPortsPerTask: Map[String, Seq[Int]] = instance.tasksMap.iterator.map {
      case (EphemeralTaskId(_, Some(ctName)), task) =>
        val ports: Seq[Int] = task.status.networkInfo.hostPorts
        ctName -> ports
      case (TaskIdWithIncarnation(_, Some(ctName), _), task) =>
        val ports: Seq[Int] = task.status.networkInfo.hostPorts
        ctName -> ports
      case (other, _) =>
        throw new IllegalStateException(s"Unsupported task id: ${other}")
    }.toMap

    allocatedPortsPerTask should be(expectedHostPortsPerCT)

    instance.tasksMap.foreach {
      case (_, task) =>
        task.status.stagedAt should be(clock.now())
        task.status.condition should be(Condition.Provisioned)
    }
  }
}

object InstanceOpFactoryImplTest {

  val someRes = Resources(1, 128, 0, 0)

  val fakeAllocatedPort = 99

  val minimalPod = PodDefinition(
    id = AbsolutePathId("/foo"),
    containers = Seq(MesosContainer(name = "ct1", resources = someRes)),
    role = "*"
  )

  val host = "agent1"
  val agentInfo = AgentInfo(host, None, None, None, Seq.empty)

  case class TestCase(pod: PodDefinition, agentInfo: AgentInfo) {
    val instanceId: Instance.Id = Instance.Id.forRunSpec(pod.id)

    val taskIDs: Seq[Task.Id] = pod.containers.iterator.map { ct =>
      Task.Id(instanceId, Some(ct))
    }.toSeq

    // faking it: we always get the host port that we try to allocate
    val hostPortsAllocatedFromOffer: Seq[Option[Int]] = pod.containers.flatMap(_.endpoints.map(_.hostPort)).map {
      case Some(port) if port == 0 => Some(fakeAllocatedPort)

      // again, isn't there more compact way to do this?
      case Some(port) => Some(port)
      case None => None
    }

    val networkInfos: Map[Task.Id, NetworkInfo] = TaskGroupBuilder.buildTaskNetworkInfos(pod, host, taskIDs, hostPortsAllocatedFromOffer)
  }
}
