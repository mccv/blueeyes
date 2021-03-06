package blueeyes.persistence.mongo.mock

import org.spex.Specification
import blueeyes.json.JsonAST._
import com.mongodb.MongoException
import MockMongoUpdateEvaluators._
import blueeyes.persistence.mongo._

class PushAllFieldEvaluatorSpec  extends Specification{
  "create new Array for not existing field" in {
    val operation = "foo" pushAll (MongoPrimitiveInt(2))
    PushAllFieldEvaluator(JNothing, operation.filter) mustEqual(JArray(JInt(2) :: Nil))
  }
  "add new element existing field" in {
    val operation = "foo" pushAll (MongoPrimitiveInt(3))
    PushAllFieldEvaluator(JArray(JInt(2) :: Nil), operation.filter) mustEqual(JArray(JInt(2) :: JInt(3) :: Nil))
  }
}