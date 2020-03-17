package mesosphere.marathon
package test

import java.io.{File, FileNotFoundException}
import java.time.Clock

import akka.stream.Materializer
import mesosphere.marathon.Protos.Constraint
import mesosphere.marathon.Protos.Constraint.Operator
import mesosphere.marathon.core.instance.{LocalVolumeId, Reservation}
import mesosphere.marathon.core.instance.update.InstanceChangeHandler
import mesosphere.marathon.core.launcher.impl.{ReservationLabels, TaskLabels}
import mesosphere.marathon.core.leadership.LeadershipModule
import mesosphere.marathon.core.storage.store.impl.memory.InMemoryPersistenceStore
import mesosphere.marathon.core.pod.Network
import mesosphere.marathon.core.task.Task
import mesosphere.marathon.core.task.tracker.{InstanceTracker, InstanceTrackerModule}
import mesosphere.marathon.metrics.dummy.DummyMetrics
import mesosphere.marathon.raml.Resources
import mesosphere.marathon.state.Container.Docker
import mesosphere.marathon.state.Container.PortMapping
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.{AppRepository, GroupRepository, InstanceRepository, PodRepository}
import scala.jdk.CollectionConverters._
import mesosphere.mesos.protos
import mesosphere.mesos.protos.{FrameworkID, OfferID, Range, RangesResource, Resource, ScalarResource, SlaveID}
import mesosphere.mesos.protos.Implicits._
import mesosphere.util.state.FrameworkId
import org.apache.mesos.Protos.DomainInfo
import org.apache.mesos.Protos.DomainInfo.FaultDomain
import org.apache.mesos.Protos.Resource.{AllocationInfo, DiskInfo, ReservationInfo}
import org.apache.mesos.Protos._
import org.apache.mesos.{Protos => Mesos}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.Random

object MarathonTestHelper {

  lazy val clock = Clock.systemUTC()

  lazy val metrics = DummyMetrics

  def makeConfig(args: String*): AllConf = {
    new AllConf(args.toIndexedSeq) {
      // scallop will trigger sys exit
      override protected def onError(e: Throwable): Unit = throw e
      verify()
    }
  }

  def defaultConfig(
    maxInstancesPerOffer: Int = 1,
    minReviveOffersInterval: Long = 100,
    mesosRole: Option[String] = None,
    envVarsPrefix: Option[String] = None,
    maxZkNodeSize: Option[Int] = None,
    internalStorageBackend: Option[String] = None): AllConf = {

    var args = Seq(
      "--master", "127.0.0.1:5050",
      "--max_instances_per_offer", maxInstancesPerOffer.toString,
      "--min_revive_offers_interval", minReviveOffersInterval.toString,
      "--mesos_authentication_principal", "marathon"
    )

    mesosRole.foreach(args ++= Seq("--mesos_role", _))
    maxZkNodeSize.foreach(size => args ++= Seq("--zk_max_node_size", size.toString))
    envVarsPrefix.foreach(args ++= Seq("--env_vars_prefix", _))
    internalStorageBackend.foreach(backend => args ++= Seq("--internal_store_backend", backend))
    makeConfig(args: _*)
  }

  val frameworkID: FrameworkID = FrameworkID("marathon")
  val frameworkId: FrameworkId = FrameworkId("").mergeFromProto(frameworkID)

  def makeBasicOffer(cpus: Double = 4.0, mem: Double = 16000, disk: Double = 1.0,
    beginPort: Int = 31000, endPort: Int = 32000, role: String = ResourceRole.Unreserved,
    reservation: Option[ReservationLabels] = None, gpus: Double = 0.0): Offer.Builder = {

    require(role != ResourceRole.Unreserved || reservation.isEmpty, "reserved resources cannot have role *")

    def heedReserved(resource: Mesos.Resource): Mesos.Resource = {
      reservation match {
        case Some(reservationWithLabels) =>
          val labels = reservationWithLabels.mesosLabels
          val reservation =
            Mesos.Resource.ReservationInfo.newBuilder()
              .setPrincipal("marathon")
              .setLabels(labels)
          resource.toBuilder.setReservation(reservation).build()
        case None =>
          resource
      }
    }

    val cpusResource = heedReserved(ScalarResource(Resource.CPUS, cpus, role = role))
    val gpuResource = heedReserved(ScalarResource(Resource.GPUS, gpus, role = role))
    val memResource = heedReserved(ScalarResource(Resource.MEM, mem, role = role))
    val diskResource = heedReserved(ScalarResource(Resource.DISK, disk, role = role))
    val portsResource = if (beginPort <= endPort) {
      Some(heedReserved(RangesResource(
        Resource.PORTS,
        Seq(Range(beginPort.toLong, endPort.toLong)),
        role
      )))
    } else {
      None
    }

    val allocationInfo = AllocationInfo.newBuilder().setRole("*")
    val offerBuilder = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(frameworkID)
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(gpuResource)
      .addResources(memResource)
      .addResources(diskResource)
      .setAllocationInfo(allocationInfo)

    portsResource.foreach(offerBuilder.addResources)

    offerBuilder
  }

  def makeBasicOfferWithUnavailability(startTime: Timestamp, duration: FiniteDuration = Duration(5, MINUTES)): Offer.Builder = {
    val unavailableOfferBuilder = Unavailability.newBuilder()
      .setStart(TimeInfo.newBuilder().setNanoseconds(startTime.nanos))

    if (duration.isFinite) {
      unavailableOfferBuilder.setDuration(DurationInfo.newBuilder().setNanoseconds(duration.toNanos))
    }

    MarathonTestHelper.makeBasicOffer().setUnavailability(unavailableOfferBuilder.build())
  }

  def mountSource(path: Option[String]): Mesos.Resource.DiskInfo.Source = {
    val b = Mesos.Resource.DiskInfo.Source.newBuilder.
      setType(Mesos.Resource.DiskInfo.Source.Type.MOUNT)
    path.foreach { p =>
      b.setMount(Mesos.Resource.DiskInfo.Source.Mount.newBuilder.
        setRoot(p))
    }

    b.build
  }

  def mountSource(path: String): Mesos.Resource.DiskInfo.Source =
    mountSource(Some(path))

  def mountDisk(path: Option[String]): Mesos.Resource.DiskInfo = {
    // val source = Mesos.Resource.DiskInfo.sour
    Mesos.Resource.DiskInfo.newBuilder.
      setSource(
        mountSource(path)).
        build
  }

  def mountDisk(path: String): Mesos.Resource.DiskInfo =
    mountDisk(Some(path))

  def pathSource(path: Option[String]): Mesos.Resource.DiskInfo.Source = {
    val b = Mesos.Resource.DiskInfo.Source.newBuilder.
      setType(Mesos.Resource.DiskInfo.Source.Type.PATH)
    path.foreach { p =>
      b.setPath(Mesos.Resource.DiskInfo.Source.Path.newBuilder.
        setRoot(p))
    }

    b.build
  }

  def pathSource(path: String): Mesos.Resource.DiskInfo.Source =
    pathSource(Some(path))

  def pathDisk(path: Option[String]): Mesos.Resource.DiskInfo = {
    // val source = Mesos.Resource.DiskInfo.sour
    Mesos.Resource.DiskInfo.newBuilder.
      setSource(
        pathSource(path)).
        build
  }

  def pathDisk(path: String): Mesos.Resource.DiskInfo =
    pathDisk(Some(path))

  def rawSource: Mesos.Resource.DiskInfo.Source = {
    val b = Mesos.Resource.DiskInfo.Source.newBuilder.
      setType(Mesos.Resource.DiskInfo.Source.Type.RAW)
    b.build
  }

  def rawDisk(): Mesos.Resource.DiskInfo = {
    Mesos.Resource.DiskInfo.newBuilder.
      setSource(rawSource).
      build
  }

  def blockSource: Mesos.Resource.DiskInfo.Source = {
    val b = Mesos.Resource.DiskInfo.Source.newBuilder.
      setType(Mesos.Resource.DiskInfo.Source.Type.BLOCK)
    b.build
  }

  def blockDisk(): Mesos.Resource.DiskInfo = {
    Mesos.Resource.DiskInfo.newBuilder.
      setSource(blockSource).
      build
  }

  def scalarResource(
    name: String, d: Double, role: String = ResourceRole.Unreserved,
    providerId: Option[protos.ResourceProviderID] = None, reservation: Option[ReservationInfo] = None,
    disk: Option[DiskInfo] = None): Mesos.Resource = {

    val builder = Mesos.Resource
      .newBuilder()
      .setName(name)
      .setType(Value.Type.SCALAR)
      .setScalar(Value.Scalar.newBuilder().setValue(d))
      .setRole(role): @silent

    providerId.foreach { providerId =>
      val proto = Mesos.ResourceProviderID.newBuilder().setValue(providerId.value)
      builder.setProviderId(proto)
    }
    reservation.foreach(builder.setReservation)
    disk.foreach(builder.setDisk)

    builder.build()
  }

  def portsResource(
    begin: Long, end: Long, role: String = ResourceRole.Unreserved,
    reservation: Option[ReservationInfo] = None): Mesos.Resource = {

    val ranges = Mesos.Value.Ranges.newBuilder()
      .addRange(Mesos.Value.Range.newBuilder().setBegin(begin).setEnd(end))

    val builder = Mesos.Resource
      .newBuilder()
      .setName(Resource.PORTS)
      .setType(Value.Type.RANGES)
      .setRanges(ranges)
      .setRole(role): @silent

    reservation.foreach(builder.setReservation)

    builder.build()
  }

  def reservation(principal: String, labels: Map[String, String] = Map.empty): Mesos.Resource.ReservationInfo = {
    Mesos.Resource.ReservationInfo.newBuilder()
      .setPrincipal(principal)
      .setLabels(labels.toMesosLabels)
      .build()
  }

  def constraint(field: String, operator: String, value: Option[String]): Constraint = {
    val b = Constraint.newBuilder.
      setField(field).
      setOperator(Operator.valueOf(operator))
    value.foreach(b.setValue)
    b.build
  }

  def reservedDisk(id: String, size: Double = 4096, role: String = ResourceRole.Unreserved,
    principal: String = "test", containerPath: String = "/container"): Mesos.Resource.Builder = {
    Mesos.Resource.newBuilder()
      .setType(Mesos.Value.Type.SCALAR)
      .setName(Resource.DISK)
      .setScalar(Mesos.Value.Scalar.newBuilder.setValue(size))
      .setRole(role)
      .setReservation(ReservationInfo.newBuilder().setPrincipal(principal))
      .setDisk(DiskInfo.newBuilder()
        .setPersistence(DiskInfo.Persistence.newBuilder().setId(id))
        .setVolume(Mesos.Volume.newBuilder()
          .setMode(Mesos.Volume.Mode.RW)
          .setContainerPath(containerPath)
        )
      ): @silent
  }

  def newDomainInfo(region: String, zone: String): DomainInfo = {
    DomainInfo.newBuilder
      .setFaultDomain(
        FaultDomain.newBuilder
          .setZone(FaultDomain.ZoneInfo.newBuilder.setName(zone))
          .setRegion(FaultDomain.RegionInfo.newBuilder.setName(region)))
      .build
  }

  /**
    * @param ranges how many port ranges should be included in this offer
    * @return
    */
  def makeBasicOfferWithManyPortRanges(ranges: Int): Offer.Builder = {
    val role = ResourceRole.Unreserved
    val cpusResource = ScalarResource(Resource.CPUS, 4.0, role = role)
    val memResource = ScalarResource(Resource.MEM, 16000, role = role)
    val diskResource = ScalarResource(Resource.DISK, 1.0, role = role)
    val portsResource = RangesResource(
      Resource.PORTS,
      List.tabulate(ranges)(_ * 2 + 1).map(p => Range(p.toLong, (p + 1).toLong)),
      role
    )

    val offerBuilder = Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(frameworkID)
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(portsResource)

    offerBuilder
  }

  def makeBasicOfferWithRole(cpus: Double, mem: Double, disk: Double,
    beginPort: Int, endPort: Int, role: String) = {
    val portsResource = RangesResource(
      Resource.PORTS,
      Seq(Range(beginPort.toLong, endPort.toLong)),
      role
    )
    val cpusResource = ScalarResource(Resource.CPUS, cpus, role)
    val memResource = ScalarResource(Resource.MEM, mem, role)
    val diskResource = ScalarResource(Resource.DISK, disk, role)
    Offer.newBuilder
      .setId(OfferID("1"))
      .setFrameworkId(frameworkID)
      .setSlaveId(SlaveID("slave0"))
      .setHostname("localhost")
      .addResources(cpusResource)
      .addResources(memResource)
      .addResources(diskResource)
      .addResources(portsResource)
  }

  def makeOneCPUTask(taskId: String): TaskInfo.Builder = makeOneCPUTask(TaskID.newBuilder().setValue(taskId).build())
  def makeOneCPUTask(taskId: Task.Id): TaskInfo.Builder = makeOneCPUTask(TaskID.newBuilder().setValue(taskId.idString).build())
  def makeOneCPUTask(taskId: TaskID): TaskInfo.Builder = {
    TaskInfo.newBuilder()
      .setName("true")
      .setTaskId(taskId)
      .setSlaveId(SlaveID("slave1"))
      .setCommand(CommandInfo.newBuilder().setShell(true).addArguments("true"))
      .addResources(ScalarResource(Resource.CPUS, 1.0, ResourceRole.Unreserved))
  }

  def makeBasicApp(id: AbsolutePathId = AbsolutePathId("/test-app")) = AppDefinition(
    id,
    cmd = Some("sleep 60"),
    resources = Resources(cpus = 1.0, mem = 64.0, disk = 1.0),
    executor = "//cmd",
    portDefinitions = Seq(PortDefinition(0)),
    role = "*"
  )

  def createTaskTrackerModule(
    leadershipModule: LeadershipModule,
    instanceStore: Option[InstanceRepository] = None,
    groupStore: Option[GroupRepository] = None)(implicit mat: Materializer): InstanceTrackerModule = {

    implicit val ctx = ExecutionContext.Implicits.global
    val instanceRepo = instanceStore.getOrElse {
      val store = new InMemoryPersistenceStore(metrics)
      store.markOpen()
      InstanceRepository.inMemRepository(store)
    }
    val groupRepo = groupStore.getOrElse {
      // See [[mesosphere.marathon.storage.repository.GcActorTest]]
      val store = new InMemoryPersistenceStore(metrics)
      store.markOpen()
      val maxVersionsCacheSize = 1000
      val appRepo = AppRepository.inMemRepository(store)
      val podRepo = PodRepository.inMemRepository(store)
      GroupRepository.inMemRepository(store, appRepo, podRepo, maxVersionsCacheSize, RootGroup.NewGroupStrategy.Fail)
    }
    val updateSteps = Seq.empty[InstanceChangeHandler]

    val crashStrategy = new TestCrashStrategy
    new InstanceTrackerModule(metrics, clock, defaultConfig(), leadershipModule, instanceRepo, groupRepo, updateSteps, crashStrategy) {
      // some tests create only one actor system but create multiple task trackers
      override protected lazy val instanceTrackerActorName: String = s"taskTracker_${Random.alphanumeric.take(10).mkString}"
    }
  }

  def createTaskTracker(
    leadershipModule: LeadershipModule,
    instanceStore: Option[InstanceRepository] = None,
    groupStore: Option[GroupRepository] = None)(implicit mat: Materializer): InstanceTracker = {
    createTaskTrackerModule(leadershipModule, instanceStore, groupStore).instanceTracker
  }

  def persistentVolumeResources(reservationId: Reservation.Id, localVolumeIds: LocalVolumeId*) = localVolumeIds.map { id =>
    Mesos.Resource.newBuilder()
      .setName("disk")
      .setType(Mesos.Value.Type.SCALAR)
      .setScalar(Mesos.Value.Scalar.newBuilder().setValue(10))
      .setRole("test")
      .setReservation(
        Mesos.Resource.ReservationInfo
          .newBuilder()
          .setPrincipal("principal")
          .setLabels(TaskLabels.labelsForTask(frameworkId, reservationId).mesosLabels)
      )
      .setDisk(Mesos.Resource.DiskInfo.newBuilder()
        .setPersistence(Mesos.Resource.DiskInfo.Persistence.newBuilder().setId(id.idString))
        .setVolume(Mesos.Volume.newBuilder()
          .setContainerPath(id.name)
          .setMode(Mesos.Volume.Mode.RW)))
      .build(): @silent
  }

  def offerWithVolumes(reservationId: Reservation.Id, localVolumeIds: LocalVolumeId*) = {
    MarathonTestHelper.makeBasicOffer(
      reservation = Some(TaskLabels.labelsForTask(frameworkId, reservationId)),
      role = "test"
    ).addAllResources(persistentVolumeResources(reservationId, localVolumeIds: _*).asJava).build()
  }

  def offerWithVolumes(taskId: Task.Id, hostname: String, agentId: String, localVolumeIds: LocalVolumeId*) = {
    MarathonTestHelper.makeBasicOffer(
      reservation = Some(TaskLabels.labelsForTask(frameworkId, Reservation.SimplifiedId(taskId.instanceId))),
      role = "test"
    ).setHostname(hostname)
      .setSlaveId(Mesos.SlaveID.newBuilder().setValue(agentId).build())
      .addAllResources(persistentVolumeResources(Reservation.SimplifiedId(taskId.instanceId), localVolumeIds: _*).asJava).build()
  }

  def offerWithVolumesOnly(taskId: Task.Id, localVolumeIds: LocalVolumeId*) = {
    MarathonTestHelper.makeBasicOffer()
      .clearResources()
      .addAllResources(persistentVolumeResources(Reservation.SimplifiedId(taskId.instanceId), localVolumeIds: _*).asJava)
      .build()
  }

  def addVolumesToOffer(offer: Offer.Builder, taskId: Task.Id, localVolumeIds: LocalVolumeId*): Offer.Builder = {
    offer
      .addAllResources(persistentVolumeResources(Reservation.SimplifiedId(taskId.instanceId), localVolumeIds: _*).asJava)
  }

  def appWithPersistentVolume(): AppDefinition = {
    MarathonTestHelper.makeBasicApp().copy(
      container = Some(mesosContainerWithPersistentVolume)
    )
  }

  def mesosContainerWithPersistentVolume = Container.Mesos(
    volumes = Seq(VolumeWithMount(
      volume = PersistentVolume(name = None, persistent = PersistentVolumeInfo(10)), // must match persistentVolumeResources
      mount = VolumeMount(volumeName = None, mountPath = "persistent-volume"))))

  def mesosIpAddress(ipAddress: String) = {
    Mesos.NetworkInfo.IPAddress.newBuilder().setIpAddress(ipAddress).build
  }

  def networkInfoWithIPAddress(ipAddress: Mesos.NetworkInfo.IPAddress) = {
    Mesos.NetworkInfo.newBuilder().addIpAddresses(ipAddress).build
  }

  def containerStatusWithNetworkInfo(networkInfo: Mesos.NetworkInfo) = {
    Mesos.ContainerStatus.newBuilder().addNetworkInfos(networkInfo).build
  }

  object Implicits {
    implicit class AppDefinitionImprovements(app: AppDefinition) {
      def withPortDefinitions(portDefinitions: Seq[PortDefinition]): AppDefinition =
        app.copy(portDefinitions = portDefinitions)

      def withNoPortDefinitions(): AppDefinition = app.withPortDefinitions(Seq.empty)

      def withDockerNetworks(networks: Network*): AppDefinition = {
        val docker = app.container.getOrElse(Container.Mesos()) match {
          case docker: Docker => docker
          case _ => Docker(image = "busybox")
        }

        app.copy(container = Some(docker), networks = networks.to(Seq))
      }

      def withPortMappings(newPortMappings: Seq[PortMapping]): AppDefinition = {
        val container = app.container.getOrElse(Container.Mesos())
        val docker = (container match {
          case c: Docker => c
          case _ => Docker(image = "busybox")
        }).copy(portMappings = newPortMappings)

        app.copy(container = Some(docker))
      }

      def withHealthCheck(healthCheck: mesosphere.marathon.core.health.HealthCheck): AppDefinition =
        app.copy(healthChecks = Set(healthCheck))
    }

    implicit class TaskImprovements(task: Task) {
      def withNetworkInfo(networkInfo: core.task.state.NetworkInfo): Task = {
        val newStatus = task.status.copy(networkInfo = networkInfo)
        task.copy(status = newStatus)
      }

      def withNetworkInfo(hostName: Option[String] = None, hostPorts: Seq[Int] = Nil, networkInfos: scala.collection.Seq[NetworkInfo] = Nil): Task = {
        def containerStatus(networkInfos: scala.collection.Seq[NetworkInfo]) = {
          Mesos.ContainerStatus.newBuilder().addAllNetworkInfos(networkInfos.asJava)
        }
        def mesosStatus(taskId: Task.Id, mesosStatus: Option[TaskStatus], networkInfos: scala.collection.Seq[NetworkInfo]): Option[TaskStatus] = {
          val taskState = mesosStatus.fold(TaskState.TASK_STAGING)(_.getState)
          Some(mesosStatus.getOrElse(Mesos.TaskStatus.getDefaultInstance).toBuilder
            .setContainerStatus(containerStatus(networkInfos))
            .setState(taskState)
            .setTaskId(taskId.mesosTaskId)
            .build)
        }
        val taskStatus = mesosStatus(task.taskId, task.status.mesosStatus, networkInfos)
        val ipAddresses: Seq[Mesos.NetworkInfo.IPAddress] = networkInfos.iterator.flatMap(_.getIpAddressesList.asScala).toSeq
        val initialNetworkInfo = core.task.state.NetworkInfo(
          hostName.getOrElse("host.some"),
          hostPorts = hostPorts,
          ipAddresses = ipAddresses)
        val networkInfo = taskStatus.fold(initialNetworkInfo)(initialNetworkInfo.update)
        withNetworkInfo(networkInfo).withStatus(_.copy(mesosStatus = taskStatus))
      }

      def withStatus(update: Task.Status => Task.Status): Task = task.copy(status = update(task.status))
    }
  }

  def resourcePath(resourceName: String): File = {
    val classLoader = getClass.getClassLoader

    Option(classLoader.getResource(resourceName)) match {
      case Some(fileName) =>
        new File(fileName.getFile)
      case None =>
        throw new FileNotFoundException(s"Could not find resource ${resourceName}")
    }
  }
}
