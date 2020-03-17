package mesosphere.marathon
package api.v2

import akka.Done
import java.util.Collections

import akka.stream.scaladsl.Source
import mesosphere.AkkaUnitTest
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.api.{GroupApiService, TestAuthFixture, TestGroupManagerFixture}
import mesosphere.marathon.core.appinfo._
import mesosphere.marathon.core.group.GroupManager
import mesosphere.marathon.raml.{App, GroupUpdate}
import mesosphere.marathon.state.PathId._
import mesosphere.marathon.state._
import mesosphere.marathon.storage.repository.GroupRepository
import mesosphere.marathon.test.{GroupCreation, JerseyTest}
import mesosphere.marathon.util.ScallopStub
import org.scalatest.Inside
import play.api.libs.json.{JsObject, Json}

import scala.concurrent.Future
import scala.concurrent.duration._

class GroupsResourceTest extends AkkaUnitTest with GroupCreation with JerseyTest with Inside {
  case class Fixture(
      config: MarathonConf = mock[MarathonConf],
      groupManager: GroupManager = mock[GroupManager],
      groupRepository: GroupRepository = mock[GroupRepository],
      auth: TestAuthFixture = new TestAuthFixture,
      groupInfo: GroupInfoService = mock[GroupInfoService],
      groupApiService: GroupApiService = mock[GroupApiService],
      embed: java.util.Set[String] = Collections.emptySet[String]) {
    config.zkTimeoutDuration returns (patienceConfig.timeout.toMillis * 2).millis
    config.availableFeatures returns Set.empty
    config.defaultNetworkName returns ScallopStub(None)
    config.mesosBridgeName returns ScallopStub(Some("default-mesos-bridge-name"))
    val groupsResource: GroupsResource = new GroupsResource(groupManager, groupInfo, config, groupApiService)(auth.auth, auth.auth, mat, ctx)
  }

  case class FixtureWithRealGroupManager(
      initialRoot: Group = Group.empty("/".toAbsolutePath),
      groupInfo: GroupInfoService = mock[GroupInfoService],
      auth: TestAuthFixture = new TestAuthFixture) {
    val config = AllConf.withTestConfig("--zk_timeout", "3000")
    val initialRootGroup = RootGroup.fromGroup(initialRoot, RootGroup.NewGroupStrategy.fromConfig(config.newGroupEnforceRole()))
    val f = new TestGroupManagerFixture(config = config, initialRoot = initialRootGroup)
    val groupRepository: GroupRepository = f.groupRepository
    val groupManager: GroupManager = f.groupManager

    f.schedulerProvider.get().listRunningDeployments() returns Future.successful(Seq.empty)

    implicit val authorizer = auth.auth

    val groupsResource: GroupsResource = new GroupsResource(groupManager, groupInfo, config, new GroupApiService(groupManager))(auth.auth, auth.auth, mat, ctx)
  }

  "GroupsResource" should {
    "dry run update" in new FixtureWithRealGroupManager {
      Given("A real Group Manager with no groups")

      val app = App(id = "/test/app", cmd = Some("test cmd"))
      val update = GroupUpdate(id = Some("/test"), apps = Some(Set(app)))

      When("Doing a dry run update")
      val body = Json.stringify(Json.toJson(update)).getBytes
      val result = asyncRequest { r =>
        groupsResource.update("/test", force = false, dryRun = true, body, auth.request, r)
      }
      val json = Json.parse(result.getEntity.toString)

      Then("The deployment plan is correct")
      val steps = (json \ "steps").as[Seq[JsObject]]
      assert(steps.size == 2)

      val firstStep = (steps.head \ "actions").as[Seq[JsObject]].head
      assert((firstStep \ "action").as[String] == "StartApplication")
      assert((firstStep \ "app").as[String] == "/test/app")

      val secondStep = (steps.last \ "actions").as[Seq[JsObject]].head
      assert((secondStep \ "action").as[String] == "ScaleApplication")
      assert((secondStep \ "app").as[String] == "/test/app")
    }

    "dry run update on an existing group" in new FixtureWithRealGroupManager {
      Given("A real Group Manager with no groups")
      val app = App(id = "/foo/bla/app", cmd = Some("test cmd"))
      val update = GroupUpdate(id = Some("/foo/bla"), apps = Some(Set(app)))

      When("Doing a dry run update")
      val body = Json.stringify(Json.toJson(update)).getBytes
      val result = asyncRequest { r =>
        groupsResource.update("/foo/bla", force = false, dryRun = true, body, auth.request, r)
      }
      val json = Json.parse(result.getEntity.toString)

      Then("The deployment plan is correct")
      val steps = (json \ "steps").as[Seq[JsObject]]
      assert(steps.size == 2)

      val firstStep = (steps.head \ "actions").as[Seq[JsObject]].head
      assert((firstStep \ "action").as[String] == "StartApplication")
      assert((firstStep \ "app").as[String] == "/foo/bla/app")

      val secondStep = (steps.last \ "actions").as[Seq[JsObject]].head
      assert((secondStep \ "action").as[String] == "ScaleApplication")
      assert((secondStep \ "app").as[String] == "/foo/bla/app")
    }

    "access without authentication is denied" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = false
      val req = auth.request
      val body = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""

      groupManager.rootGroup() returns Builders.newRootGroup()

      When("the root is fetched from index")
      val root = asyncRequest { r =>
        groupsResource.root(req, embed, r)
      }

      Then("we receive a NotAuthenticated response")
      root.getStatus should be(auth.NotAuthenticatedStatus)

      When("the group by id is fetched from create")
      val rootGroup = asyncRequest { r =>
        groupsResource.group("/foo/bla", embed, req, r)
      }
      Then("we receive a NotAuthenticated response")
      rootGroup.getStatus should be(auth.NotAuthenticatedStatus)

      When("the root group is created")
      val create = asyncRequest { r =>
        groupsResource.create(false, body.getBytes("UTF-8"), req, r)
      }
      Then("we receive a NotAuthenticated response")
      create.getStatus should be(auth.NotAuthenticatedStatus)

      When("the group is created")
      val createWithPath = asyncRequest { r =>
        groupsResource.createWithPath("/my/id", false, body.getBytes("UTF-8"), req, r)
      }
      Then("we receive a NotAuthenticated response")
      createWithPath.getStatus should be(auth.NotAuthenticatedStatus)

      When("the root group is updated")
      val updateRoot = asyncRequest { r =>
        groupsResource.updateRoot(false, false, body.getBytes("UTF-8"), req, r)
      }
      Then("we receive a NotAuthenticated response")
      updateRoot.getStatus should be(auth.NotAuthenticatedStatus)

      When("the group is updated")
      val update = asyncRequest { r =>
        groupsResource.update("", false, false, body.getBytes("UTF-8"), req, r)
      }
      Then("we receive a NotAuthenticated response")
      update.getStatus should be(auth.NotAuthenticatedStatus)

      When("the root group is deleted")
      val deleteRoot = asyncRequest { r =>
        groupsResource.delete(false, req, r)
      }
      Then("we receive a NotAuthenticated response")
      deleteRoot.getStatus should be(auth.NotAuthenticatedStatus)

      When("the group is deleted")
      val delete = asyncRequest { r =>
        groupsResource.delete("", false, req, r)
      }
      Then("we receive a NotAuthenticated response")
      delete.getStatus should be(auth.NotAuthenticatedStatus)
    }

    "access without authorization is denied if the resource exists" in new FixtureWithRealGroupManager {
      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      val body = """{"id":"/a/b/c","cmd":"foo","ports":[]}"""

      When("the root group is created")
      val create = asyncRequest { r =>
        groupsResource.create(false, body.getBytes("UTF-8"), req, r)
      }
      Then("we receive a Not Authorized response")
      create.getStatus should be(auth.UnauthorizedStatus)

      When("the group is created")
      val createWithPath = asyncRequest { r =>
        groupsResource.createWithPath("/my/id", false, body.getBytes("UTF-8"), req, r)
      }
      Then("we receive a Not Authorized response")
      createWithPath.getStatus should be(auth.UnauthorizedStatus)

      When("the root group is updated")
      val updateRoot = asyncRequest { r =>
        groupsResource.updateRoot(false, false, body.getBytes("UTF-8"), req, r)
      }
      Then("we receive a Not Authorized response")
      updateRoot.getStatus should be(auth.UnauthorizedStatus)

      When("the group is updated")
      val update = asyncRequest { r =>
        groupsResource.update("", false, false, body.getBytes("UTF-8"), req, r)
      }
      Then("we receive a Not Authorized response")
      update.getStatus should be(auth.UnauthorizedStatus)

      When("the root group is deleted")
      val deleteRoot = asyncRequest { r =>
        groupsResource.delete(false, req, r)
      }
      Then("we receive a Not Authorized response")
      deleteRoot.getStatus should be(auth.UnauthorizedStatus)

      When("the group is deleted")
      val delete = asyncRequest { r =>
        groupsResource.delete("", false, req, r)
      }
      Then("we receive a Not Authorized response")
      delete.getStatus should be(auth.UnauthorizedStatus)
    }

    "access to root group without authentication is allowed" in new Fixture {
      Given("An unauthenticated request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request
      groupInfo.selectGroup(any, any, any, any) returns Future.successful(None)

      When("the root is fetched from index")
      val root = asyncRequest { r => groupsResource.root(req, embed, r) }

      Then("the request is successful")
      root.getStatus should be(200)
    }

    "authenticated delete without authorization leads to a 404 if the resource doesn't exist" in new FixtureWithRealGroupManager {
      Given("A real group manager with no apps")

      Given("An unauthorized request")
      auth.authenticated = true
      auth.authorized = false
      val req = auth.request

      When("the group is deleted")
      Then("we get a 404")
      // FIXME (gkleiman): this leads to an ugly stack trace
      val response = asyncRequest { r =>
        groupsResource.delete("/foo", false, req, r)
      }
      response.getStatus shouldBe 404
    }

    "Group Versions for root are transferred as simple json string array (Fix #2329)" in new Fixture {
      Given("Specific Group versions")
      val groupVersions = Seq(Timestamp.now(), Timestamp.now())
      groupManager.versions(PathId.root) returns Source(groupVersions)
      groupManager.group(PathId.root) returns Some(createGroup(PathId.root))

      When("The versions are queried")
      val rootVersionsResponse = asyncRequest { r => groupsResource.group("versions", embed, auth.request, r) }

      Then("The versions are send as simple json array")
      rootVersionsResponse.getStatus should be (200)
      rootVersionsResponse.getEntity.toString should be(Json.toJson(groupVersions).toString())
    }

    "Group Versions for path are transferred as simple json string array (Fix #2329)" in new Fixture {
      Given("Specific group versions")
      val groupVersions = Seq(Timestamp.now(), Timestamp.now())
      groupManager.versions(any) returns Source(groupVersions)
      groupManager.versions("/foo/bla/blub".toAbsolutePath) returns Source(groupVersions)
      groupManager.group("/foo/bla/blub".toAbsolutePath) returns Some(createGroup("/foo/bla/blub".toAbsolutePath))

      When("The versions are queried")
      val rootVersionsResponse = asyncRequest { r => groupsResource.group("/foo/bla/blub/versions", embed, auth.request, r) }

      Then("The versions are send as simple json array")
      rootVersionsResponse.getStatus should be (200)
      rootVersionsResponse.getEntity.toString should be(Json.toJson(groupVersions).toString())
    }

    "Creation of a group with same path as an existing app should be prohibited (fixes #3385)" in new FixtureWithRealGroupManager(
      initialRoot = {
        val app = Builders.newAppDefinition.command("/group/app".toAbsolutePath)
        Builders.newRootGroup(apps = Seq(app))
      }
    ) {
      Given("A real group manager with one app")

      When("creating a group with the same path existing app")
      val body = Json.stringify(Json.toJson(GroupUpdate(id = Some("/group/app"))))

      Then("we get a 409")
      val response = asyncRequest { r =>
        groupsResource.create(false, body.getBytes, auth.request, r)
      }
      response.getStatus shouldBe 409
    }

    "Creation of a group with same path as an existing group should be prohibited" in
      new FixtureWithRealGroupManager(initialRoot = Builders.newRootGroup(groupIds = Seq("/group".toAbsolutePath))) {
        When("creating a group with the same path existing app")
        val body = Json.stringify(Json.toJson(GroupUpdate(id = Some("/group"))))

        Then("we get a 409")
        val response = asyncRequest { r =>
          groupsResource.create(false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 409
      }

    def groupPaths(rootGroup: RootGroup): Set[String] = {
      rootGroup.transitiveGroups().map(_._1.toString).toSet + rootGroup.id.toString
    }
    "Creation of a top-level relative group path creates the group in the root" in {
      new FixtureWithRealGroupManager(initialRoot = Builders.newRootGroup()) {
        f.service.deploy(any, any).returns(Future(Done))
        When("creating a group without an absolute path")
        val body = Json.stringify(Json.toJson(GroupUpdate(id = Some("relative"))))

        Then("we get a 200")
        val response = asyncRequest { r =>
          groupsResource.create(false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 201
        val rootGroup = groupManager.rootGroup()
        println(rootGroup)
        // Before MARATHON-8017 was fixed, the above post would create the group as /relative/relative
        groupPaths(rootGroup) shouldBe Set("/", "/relative")
      }
    }

    "Creation of a relative group path inside of a specified parent group creates the group in the parent group" in {
      new FixtureWithRealGroupManager(initialRoot = Builders.newRootGroup()) {
        f.service.deploy(any, any).returns(Future(Done))
        When("creating a group without an absolute path")
        val body = Json.stringify(Json.toJson(GroupUpdate(id = Some("child"))))

        When("Specifying a rootPath of /parent")
        val response = asyncRequest { r =>
          groupsResource.createWithPath("/parent", false, body.getBytes, auth.request, r)
        }
        Then("we get a 201")
        response.getStatus shouldBe 201
        val rootGroup = groupManager.rootGroup()
        groupPaths(rootGroup) shouldBe Set("/", "/parent", "/parent/child")
      }
    }

    "Rejects group updates with apps that don't belong directly to a group" in {
      new FixtureWithRealGroupManager(initialRoot = Builders.newRootGroup()) {
        val body = """{
          "id": "sub",
          "apps": [
            {
              "id": "lol/bibi",
              "cmd": "sleep 1200",
              "cpus": 0.1,
              "mem": 128,
              "disk": 0,
              "instances": 1
            }
          ]
        }"""
        f.service.deploy(any, any).returns(Future(Done))

        val response = asyncRequest { r =>
          groupsResource.createWithPath("/foo", false, body.getBytes, auth.request, r)
        }
        response.getEntity.toString.should(include("Identifier is not child of '/foo/sub'"))
        response.getStatus shouldBe 422
      }
    }

    "Allows group updates with apps directly in a group" in {
      new FixtureWithRealGroupManager(initialRoot = Builders.newRootGroup()) {
        val body = """{
          "id": "sub",
          "apps": [
            {
              "id": "bibi",
              "cmd": "sleep 1200",
              "cpus": 0.1,
              "mem": 128,
              "disk": 0,
              "instances": 1
            }
          ]
        }"""
        f.service.deploy(any, any).returns(Future(Done))

        val response = asyncRequest { r =>
          groupsResource.createWithPath("/foo", false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 201

        val rootGroup = groupManager.rootGroup()
        groupPaths(rootGroup) shouldBe Set("/", "/foo", "/foo/sub", "/foo/sub")
        rootGroup.app(AbsolutePathId("/foo/sub/bibi")).shouldNot(be(empty))
      }
    }

    "Allows group updates with mid-level groups" in {
      new FixtureWithRealGroupManager(initialRoot = Builders.newRootGroup()) {
        val body = """
        {
          "groups": [
            {
              "apps": [
                {
                  "id": "goodnight",
                  "cmd": "sleep 1",
                  "instances": 0
                }
              ],
              "id": "sleep"
            }
          ],
          "id": "/test-group"
        }"""
        f.service.deploy(any, any).returns(Future(Done))

        val response = asyncRequest { r =>
          groupsResource.createWithPath("", false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 201

        val rootGroup = groupManager.rootGroup()
        groupPaths(rootGroup) shouldBe Set("/", "/test-group", "/test-group/sleep")
        rootGroup.app(AbsolutePathId("/test-group/sleep/goodnight")).shouldNot(be(empty))
      }
    }

    "Allow batch creation of a top-level group with enforce role and apps" in {
      new FixtureWithRealGroupManager(initialRoot = Builders.newRootGroup()) {
        val body =
          """
        {
          "groups": [
            {
              "apps": [
                {
                  "id": "goodnight",
                  "cmd": "sleep 1",
                  "instances": 0,
                  "role": "prod"
                }
              ],
              "id": "sleep"
            }
          ],
          "id": "/prod",
          "enforceRole": true
        }"""
        f.service.deploy(any, any).returns(Future(Done))

        val response = asyncRequest { r =>
          groupsResource.createWithPath("", false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 201

        val rootGroup = groupManager.rootGroup()
        groupPaths(rootGroup) shouldBe Set("/", "/prod", "/prod/sleep")
        rootGroup.app(AbsolutePathId("/prod/sleep/goodnight")).value.role should be("prod")
      }
    }

    "Fail a batch update when apps are modified and enforceRole is changed for an unrelated group" in {
      new FixtureWithRealGroupManager(initialRoot = createRootGroup(groups = Set(Group(id = AbsolutePathId("/dev"), enforceRole = false)))) {
        val body =
          """
        {
          "groups": [
            {
              "id": "unrelated",
              "apps": [
                {
                  "id": "goodnight",
                  "cmd": "sleep 1",
                  "instances": 0
                }
              ]
            },
            {
              "id": "/dev",
              "enforceRole": true
            }
          ],
          "id": "/"
        }"""
        f.service.deploy(any, any).returns(Future(Done))

        val response = asyncRequest { r =>
          groupsResource.createWithPath("", false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 422
        response.getEntity.toString.should(include(Group.disallowEnforceRoleChangeIfServicesChanged.EnforceRoleCantBeChangedMessage))
      }
    }

    "allow an update to enforceRole when id is not specified" in {
      val group = Group("/dev".toAbsolutePath, enforceRole = false)
      new FixtureWithRealGroupManager(initialRoot = RootGroup(groupsById = Map(group.id -> group))) {
        val body = """{"enforceRole": true}"""
        f.service.deploy(any, any).returns(Future(Done))

        val response = asyncRequest { r =>
          groupsResource.update("/dev", false, false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 200
        inside(groupManager.rootGroup().group("/dev".toAbsolutePath)) {
          case Some(devGroup) =>
            devGroup.enforceRole shouldBe true
        }
      }
    }

    "Fail a batch update when app role is invalid" in {
      new FixtureWithRealGroupManager(initialRoot = Builders.newRootGroup()) {
        val body =
          """
        {
          "groups": [
            {
              "apps": [
                {
                  "id": "goodnight",
                  "cmd": "sleep 1",
                  "instances": 0,
                  "role": "invalid"
                }
              ],
              "id": "sleep"
            }
          ],
          "id": "/prod",
          "enforceRole": true
        }"""
        f.service.deploy(any, any).returns(Future(Done))

        val response = asyncRequest { r =>
          groupsResource.createWithPath("", false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 422
      }
    }

    "Default according to the top-level group enforce role field" in {
      new FixtureWithRealGroupManager(initialRoot = createRootGroup(groups = Set())) {
        val body =
          """
        {
          "groups": [
            {
              "apps": [
                {
                  "id": "goodnight",
                  "cmd": "sleep 1",
                  "instances": 0
                }
              ],
              "id": "sleep"
            }
          ],
          "id": "/prod",
          "enforceRole": true
        }"""
        f.service.deploy(any, any).returns(Future(Done))

        val response = asyncRequest { r =>
          groupsResource.createWithPath("", false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 201

        val rootGroup = groupManager.rootGroup()
        groupPaths(rootGroup) shouldBe Set("/", "/prod", "/prod/sleep")
        rootGroup.app(AbsolutePathId("/prod/sleep/goodnight")).value.role should be("prod")
      }
    }

    "Default according to the top-level group when enforce role = false" in {
      new FixtureWithRealGroupManager(initialRoot = Builders.newRootGroup()) {
        val body =
          """
        {
          "groups": [
            {
              "apps": [
                {
                  "id": "goodnight",
                  "cmd": "sleep 1",
                  "instances": 0
                }
              ],
              "id": "sleep"
            }
          ],
          "id": "/prod",
          "enforceRole": false
        }"""
        f.service.deploy(any, any).returns(Future(Done))

        val response = asyncRequest { r =>
          groupsResource.createWithPath("", false, body.getBytes, auth.request, r)
        }
        response.getStatus shouldBe 201

        val rootGroup = groupManager.rootGroup()
        groupPaths(rootGroup) shouldBe Set("/", "/prod", "/prod/sleep")
        rootGroup.app(AbsolutePathId("/prod/sleep/goodnight")).value.role should be(config.mesosRole())
      }
    }
  }
}
