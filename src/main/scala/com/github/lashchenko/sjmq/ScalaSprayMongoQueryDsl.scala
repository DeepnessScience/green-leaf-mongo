package com.github.lashchenko.sjmq

import org.mongodb.scala.MongoClient
import org.mongodb.scala.bson.BsonDocument
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.Filters
import spray.json._

import scala.language.implicitConversions
import scala.util.matching.Regex

trait ScalaSprayMongoQueryDsl {

  private implicit class BsonToJsObjectTransformer(d: Bson) {
    def toJson: JsObject = {
      d.toBsonDocument(classOf[BsonDocument], MongoClient.DEFAULT_CODEC_REGISTRY).toJson.parseJson match {
        case jObj: JsObject => jObj
        case x => throw new IllegalArgumentException("Expected JsObject, but got " + x)
      }
    }
  }

  implicit def json2bson(j: JsObject): Bson = {
    org.bson.BsonDocument.parse(j.compactPrint)
  }

  implicit def obj2JsVal[T](t: T)(implicit writer: JsonWriter[T]): JsValue = {
    t.asJsonExpanded
  }


  implicit class ObjAsJson[T](t: T) {

    private def expandJson(path: String, jsValue: JsValue): JsValue = {

      def bsonQueryWithPath(path: String, in: JsValue): Map[String, JsValue] = in match {
        case JsObject(fields) => (Map.empty[String, JsValue] /: fields) {
          case (res, (field, v)) if field.startsWith("$") =>
            res ++ (if (path.nonEmpty) Map(path -> JsObject(field -> v)) else Map(field -> v))
          case (res, (field, v)) =>
            res ++ bsonQueryWithPath(if (path.nonEmpty) s"$path.$field" else field, v)
        }
        case value: JsValue => Map(path -> value)
      }

      jsValue match {
        case _: JsObject => JsObject(bsonQueryWithPath(path, jsValue))
        case _ => jsValue
      }
    }

    def asJsonExpanded(implicit writer: JsonWriter[T]): JsValue = expandJson("", t.toJson)
    def asJsonExpanded(path: String)(implicit writer: JsonWriter[T]): JsValue = expandJson(path, t.toJson)
  }


  private def query(fieldName: String, operator: String, jsValue: JsValue): JsObject = {
    require(fieldName.nonEmpty, "FieldName should be non empty.")
    require(operator.nonEmpty, "QueryOperator should be non empty.")

    def bsonQueryWithPath(path: String, in: JsValue): Map[String, JsValue] = in match {
      case JsObject(fields) => (Map.empty[String, JsValue] /: fields) {
        case (res, (field, v)) if field.startsWith("$") => res ++ Map(path -> JsObject(operator -> JsObject(field -> v)))
        case (res, (field, v)) => res ++ bsonQueryWithPath(s"$path.$field", v)
      }
      case value: JsValue => Map(path -> JsObject(operator -> value))
    }

    JsObject(bsonQueryWithPath(fieldName, jsValue))
  }


  /**
    * $and performs a logical AND operation on an array of two or more expressions
    * (e.g. expression1, expression2, etc.) and selects the documents that satisfy all the expressions in the array.
    * The $and operator uses short-circuit evaluation.
    * If the first expression (e.g. expression1) evaluates to false, MongoDB will not evaluate the remaining expressions.
    * @see https://docs.mongodb.com/manual/reference/operator/query/and/
    *
    * @example {{{$and("price" $ne 1.99, "price" $exists true)}}}
    *
    * @param filters are expressions
    * @return the filter
    */
  def $and(filters: JsValue*): JsObject =
    JsObject("$and" -> JsArray(filters: _*))

  /**
    * The $or operator performs a logical OR operation on an array of two or more expressions and selects the documents
    * that satisfy at least one of the expressions.
    * @see https://docs.mongodb.com/manual/reference/operator/query/or/
    *
    * @example {{{$or("quantity" $lt 20, "price" $eq 10)}}}
    *
    * @param filters are expressions
    * @return the filter
    */
  def $or(filters: JsValue*): JsObject =
    JsObject("$or" -> JsArray(filters.toVector))

  /**
    * $nor performs a logical NOR operation on an array of one or more query expression and selects the documents that
    * fail all the query expressions in the array.
    * @see https://docs.mongodb.com/manual/reference/operator/query/nor/
    *
    * @example {{{$nor("price" $eq 1.99, "qty" $lt 20, "sale" $eq true)}}}
    *
    * @param filters are expressions
    * @return the filter
    */
  def $nor(filters: JsValue*): JsObject =
    JsObject("$nor" -> JsArray(filters.toVector))

  /**
    * The $elemMatch operator matches documents that contain an array field with at least one element that matches
    * all the specified query criteria.
    * @see https://docs.mongodb.com/manual/reference/operator/query/elemMatch/
    *
    * @example
    * {{{"qty" $all (
    *   $elemMatch ($and("size" $eq "M", "num" $gt 50)),
    *   $elemMatch ($and("num" $eq 100, "color" $eq "green"))
    * )
    * }}}
    * @param v is value
    * @return the filter
    */
  def $elemMatch(v: JsValue): JsObject =
    JsObject("$elemMatch" -> v)

  implicit class FiltersDsl(field: String) {

    /**
      * Specifies equality condition. The $eq operator matches documents where the value of a field equals the specified value.
      * @see https://docs.mongodb.com/manual/reference/operator/query/eq/
      *
      * @example {{{"qty" $eq 20}}}
      *
      * @param v is value
      * @return the filter
      */
    def $eq(v: JsValue): JsObject =
      query(field, "$eq", v)

    /**
      * $ne selects the documents where the value of the field is not equal to the specified value.
      * This includes documents that do not contain the field.
      * @see https://docs.mongodb.com/manual/reference/operator/query/ne/
      *
      * @example {{{"qty" $ne 20}}}
      *
      * @param v is value
      * @return the filter
      */
    def $ne(v: JsValue): JsObject =
      query(field, "$ne", v)

    /**
      * $gt selects those documents where the value of the field is greater than (i.e. >) the specified value.
      * @see https://docs.mongodb.com/manual/reference/operator/query/gt/
      *
      * @example {{{"qty" $gt 20}}}
      *
      * @param v is value
      * @return the filter
      */
    def $gt(v: JsValue): JsObject =
      query(field, "$gt", v)

    /**
      * $gte selects the documents where the value of the field is greater than or equal to (i.e. >=) a specified
      * value (e.g. value.)
      * @see https://docs.mongodb.com/manual/reference/operator/query/gte/
      *
      * @example {{{"qty" $gte 20}}}
      *
      * @param v is value
      * @return the filter
      */
    def $gte(v: JsValue): JsObject =
      query(field, "$gte", v)

    /**
      * $lt selects the documents where the value of the field is less than (i.e. <) the specified value.
      * @see https://docs.mongodb.com/manual/reference/operator/query/lt/
      *
      * @example {{{"qty" $lt 20}}}
      *
      * @param v is value
      * @return the filter
      */
    def $lt(v: JsValue): JsObject =
      query(field, "$lt", v)

    /**
      * $lte selects the documents where the value of the field is less than or equal to (i.e. <=) the specified value.
      * @see https://docs.mongodb.com/manual/reference/operator/query/lte/
      *
      * @example {{{"qty" $lte 20}}}
      *
      * @param v is value
      * @return the filter
      */
    def $lte(v: JsValue): JsObject =
      query(field, "$lte", v)

    /**
      * The $in operator selects the documents where the value of a field equals any value in the specified array.
      * @see https://docs.mongodb.com/manual/reference/operator/query/in/
      *
      * @example {{{"qty" $in (5, 15)}}}
      *
      * @param v is value
      * @return the filter
      */
    def $in(v: JsValue*): JsObject =
      JsObject(field -> JsObject("$in" -> JsArray(v: _*)))

    /**
      * $nin selects the documents where:
      * • the field value is not in the specified array or
      * • the field does not exist.
      * @see https://docs.mongodb.com/manual/reference/operator/query/nin/
      *
      * @example {{{"qty" $nin (5, 15)}}}
      *
      * @param v is value
      * @return the filter
      */
    def $nin(v: JsValue*): JsObject =
      JsObject(field -> JsObject("$nin" -> JsArray(v: _*)))

    /**
      * When exists is true, $exists matches the documents that contain the field, including documents where the
      * field value is null.
      * If exists is false, the query returns only the documents that do not contain the field.
      * @see https://docs.mongodb.com/manual/reference/operator/query/exists/
      *
      * @example {{{"qty" $exists true}}}
      *
      * @param exists is value
      * @return the filter
      */
    def $exists(exists: Boolean): JsObject =
      JsObject(field -> JsObject("$exists" -> JsBoolean(exists)))

    /**
      * Provides regular expression capabilities for pattern matching strings in queries.
      * MongoDB uses Perl compatible regular expressions (i.e. “PCRE” ) version 8.41 with UTF-8 support.
      * @see https://docs.mongodb.com/manual/reference/operator/query/regex/
      *
      * @example {{{"name" $regex "acme.*corp"}}}
      *
      * @param pattern is pattern
      * @return the filter
      */
    def $regex(pattern: String): JsObject =
      JsObject(field -> JsObject("$regex" -> JsString(pattern)))

    /**
      * Provides regular expression capabilities for pattern matching strings in queries.
      * MongoDB uses Perl compatible regular expressions (i.e. “PCRE” ) version 8.41 with UTF-8 support.
      * @see https://docs.mongodb.com/manual/reference/operator/query/regex/
      *
      * @example {{{"name" $regex ("acme.*corp", "i")}}}
      *
      * @param pattern is pattern
      * @param options is options
      * @return the filter
      */
    def $regex(pattern: String, options: String): JsObject =
      JsObject(field -> JsObject("$regex" -> JsString(pattern), "$options" -> JsString(options)))

    /**
      * Provides regular expression capabilities for pattern matching strings in queries.
      * MongoDB uses Perl compatible regular expressions (i.e. “PCRE” ) version 8.41 with UTF-8 support.
      * @see https://docs.mongodb.com/manual/reference/operator/query/regex/
      *
      * @example {{{"name" $regex "acme.*corp".r}}}
      *
      * @param regex is regular expressions value
      * @return the filter
      */
    def $regex(regex: Regex): JsObject =
      Filters.regex(field, regex).toJson

    /**
      * The $all operator selects the documents where the value of a field is an array that contains all the specified
      * elements.
      * @see https://docs.mongodb.com/manual/reference/operator/query/all/
      *
      * @example {{{"tags" $all ("ssl", "security")}}}
      *
      * @param v is value
      * @return the filter
      */
    def $all(v: JsValue*): JsObject =
      JsObject(field -> JsObject("$all" -> JsArray(v.toVector)))

    /**
      * The $elemMatch operator matches documents that contain an array field with at least one element that matches
      * all the specified query criteria.
      * @see https://docs.mongodb.com/manual/reference/operator/query/elemMatch/
      *
      * @example {{{"results" $elemMatch ("product" $eq "xyz")}}}
      *
      * @param v is value
      * @return the filter
      */
    def $elemMatch(v: JsValue): JsObject =
      JsObject(field -> JsObject("$elemMatch" -> v))

    /**
      * The $size operator matches any array with the number of elements specified by the argument.
      * @see https://docs.mongodb.com/manual/reference/operator/query/size/
      *
      * @example {{{"field" $size 2}}}
      *
      * @param size is value
      * @return the filter
      */
    def $size(size: Int): JsObject =
      JsObject(field -> JsObject("$size" -> JsNumber(size)))

    /**
      * $not performs a logical NOT operation on the specified operator-expression and selects the documents that
      * do not match the operator-expression.
      * This includes documents that do not contain the field.
      *
      * @example {{{"price" $not { _ $gt 1.99 } }}}
      *
      * @see https://docs.mongodb.com/manual/reference/operator/query/not/
      * @param filter is operator-expression.
      * @return the filter
      */
    def $not(filter: String => JsValue): JsObject =
      Filters.not(Document(filter(field).compactPrint)).toJson
  }

}

object ScalaSprayMongoQueryDsl extends ScalaSprayMongoQueryDsl