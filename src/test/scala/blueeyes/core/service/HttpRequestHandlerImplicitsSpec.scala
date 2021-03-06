package blueeyes.core.service

import org.specs.Specification

import blueeyes.json.JsonAST._
import blueeyes.util.Future
import blueeyes.core.http._

class HttpRequestHandlerImplicitsSpec extends Specification with HttpRequestHandlerImplicits {
  "HttpRequestHandlerImplicits.identifierToIdentifierWithDefault: creates IdentifierWithDefault" in {
    import HttpRequestHandlerImplicits._
    val identifierWithDefault = "foo" ?: "bar"
    identifierWithDefault.default mustEqual("bar")
  }
}

