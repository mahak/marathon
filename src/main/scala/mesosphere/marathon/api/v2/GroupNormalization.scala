package mesosphere.marathon
package api.v2

import mesosphere.marathon.state.PathId

object GroupNormalization {

  def partialUpdateNormalization(conf: MarathonConf): Normalization[raml.GroupPartialUpdate] = Normalization { update =>
    update.copy(enforceRole = Some(effectiveEnforceRole(conf, update.enforceRole)))
  }

  def updateNormalization(conf: MarathonConf, id: PathId): Normalization[raml.GroupUpdate] = Normalization { update =>
    // Only update if this is not a scale or rollback
    if (update.version.isEmpty && update.scaleBy.isEmpty) {
      if (id.parent.isRoot) {
        update.copy(enforceRole = Some(effectiveEnforceRole(conf, update.enforceRole)))
      } else update
    } else update
  }

  private def effectiveEnforceRole(conf: MarathonConf, maybeEnforceRole: Option[Boolean]): Boolean = {
    maybeEnforceRole.getOrElse {
      conf.groupRoleBehavior() match {
        case GroupRoleBehavior.Off => false
        case GroupRoleBehavior.Top => true
      }
    }
  }
}
