package mesosphere.marathon

import scala.language.implicitConversions
package object stream {
  object Implicits extends StreamConversions with ScalaConversions {
    //    implicit def toRichTraversableLike[A, Repr](t: TraversableLike[A, Repr]): RichTraversableLike[A, Repr] =
    //      new RichTraversableLike[A, Repr](t)
  }
}
