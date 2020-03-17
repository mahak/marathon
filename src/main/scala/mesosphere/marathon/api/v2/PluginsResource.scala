package mesosphere.marathon
package api.v2

import javax.inject.Inject
import javax.servlet.http.HttpServletRequest
import javax.ws.rs._
import javax.ws.rs.container.{AsyncResponse, Suspended}
import javax.ws.rs.core.{Context, MediaType, Response}
import mesosphere.marathon.api._
import mesosphere.marathon.api.v2.json.Formats._
import mesosphere.marathon.core.plugin.PluginDefinitions
import mesosphere.marathon.plugin.auth.AuthorizedResource.Plugins
import mesosphere.marathon.plugin.auth._
import mesosphere.marathon.plugin.http.HttpRequestHandler

import scala.async.Async.{async, await}
import scala.concurrent.ExecutionContext

@Path("v2/plugins")
class PluginsResource @Inject() (
    val config: MarathonConf,
    requestHandlers: Seq[HttpRequestHandler],
    definitions: PluginDefinitions
)(
    implicit
    val authenticator: Authenticator,
    val authorizer: Authorizer,
    val executionContext: ExecutionContext) extends RestResource with AuthResource {

  val pluginIdToHandler: Map[String, HttpRequestHandler] = definitions.plugins
    .iterator
    .withFilter(_.plugin == classOf[HttpRequestHandler].getName)
    .flatMap { d => requestHandlers.find(_.getClass.getName == d.implementation).map(d.id -> _) }
    .toMap

  @GET
  @Produces(Array(MediaType.APPLICATION_JSON))
  def plugins(@Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      withAuthorization(ViewResource, Plugins) {
        ok(jsonString(definitions))
      }
    }
  }

  @GET
  @Path("""{pluginId}/{path:.+}""")
  def get(
    @PathParam("pluginId") pluginId: String,
    @PathParam("path") path: String,
    @Context req: HttpServletRequest, @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      withAuthorization(ViewResource, Plugins) {
        handleRequest(pluginId, path, req)
      }
    }
  }

  @HEAD
  @Path("""{pluginId}/{path:.+}""")
  def head(
    @PathParam("pluginId") pluginId: String,
    @PathParam("path") path: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      withAuthorization(ViewResource, Plugins) {
        handleRequest(pluginId, path, req)
      }
    }
  }

  @PUT
  @Path("""{pluginId}/{path:.+}""")
  def put(
    @PathParam("pluginId") pluginId: String,
    @PathParam("path") path: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      withAuthorization(UpdateResource, Plugins) {
        handleRequest(pluginId, path, req)
      }
    }
  }

  @POST
  @Path("""{pluginId}/{path:.+}""")
  def post(
    @PathParam("pluginId") pluginId: String,
    @PathParam("path") path: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      withAuthorization(CreateResource, Plugins) {
        handleRequest(pluginId, path, req)
      }
    }
  }

  @DELETE
  @Path("""{pluginId}/{path:.+}""")
  def delete(
    @PathParam("pluginId") pluginId: String,
    @PathParam("path") path: String,
    @Context req: HttpServletRequest,
    @Suspended asyncResponse: AsyncResponse): Unit = sendResponse(asyncResponse) {
    async {
      implicit val identity = await(authenticatedAsync(req))
      withAuthorization(DeleteResource, Plugins) {
        handleRequest(pluginId, path, req)
      }
    }
  }

  private[this] def handleRequest(pluginId: String, path: String, req: HttpServletRequest): Response = {
    pluginIdToHandler.get(pluginId).map { handler =>
      val request = new RequestFacade(req, path)
      val response = new ResponseFacade
      handler.serve(request, response)
      response.response
    }.getOrElse(notFound(s"No plugin with this pluginId: $pluginId"))
  }
}
