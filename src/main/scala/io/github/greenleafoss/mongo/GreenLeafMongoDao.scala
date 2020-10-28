package io.github.greenleafoss.mongo

import GreenLeafMongoDao.DaoBsonProtocol
import org.mongodb.scala.bson.collection.immutable.Document
import org.mongodb.scala.bson.conversions.Bson
import org.mongodb.scala.model.{FindOneAndReplaceOptions, FindOneAndUpdateOptions}
import org.mongodb.scala.{Completed, FindObservable, MongoCollection, MongoDatabase, SingleObservable}
import spray.json._

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag

object GreenLeafMongoDao {
  trait DaoBsonProtocol[Id, E] {
    implicit def idFormat : JsonFormat[Id]
    implicit def entityFormat: JsonFormat[E]
  }
}

trait GreenLeafMongoDao[Id, E]
  extends GreenLeafMongoDsl
  with MongoObservableToFuture {

  protected implicit val ec: ExecutionContext

  protected val db: MongoDatabase
  protected val collection: MongoCollection[Document]

  protected val protocol: DaoBsonProtocol[Id, E]
  import protocol._

  // _id, id, key, ...
  protected val primaryKey: String = "_id"
  protected def skipNull: Boolean = true

  protected def defaultSortBy: Bson = Document("""{}""")

  def insert(e: E): Future[Completed] = {
    val d: Document = e.toJson.skipNull(skipNull)
    log.trace(s"DAO.insertOne: $d")
    collection.insertOne(d).toFuture()
  }

  def insert(entities: Seq[E]): Future[Completed] = {
    // Document([ obj1, obj2, ... ]) can't be created
    // [ Document(obj1), Document(obj2), ... ] - OK
    val documents = entities.map(d => Document(d.toJson.skipNull(skipNull).compactPrint))
    log.trace(s"DAO.insertMany: $documents")
    collection.insertMany(documents).toFuture()
  }

  protected def internalFindBy(filter: Bson, offset: Int, limit: Int, sortBy: Bson = defaultSortBy): FindObservable[Document] = {
    log.trace("DAO.internalFindBy: " + filter.toString)
    collection.find(filter).skip(offset).limit(limit).sort(sortBy)
  }

  def findOneBy(filter: Bson, offset: Int = 0, sortBy: Bson = defaultSortBy): Future[Option[E]] = {
    internalFindBy(filter, offset, limit = 1, sortBy).asOpt[E]
  }

  def findBy(filter: Bson, offset: Int = 0, limit: Int = 0, sortBy: Bson = defaultSortBy): Future[Seq[E]] = {
    internalFindBy(filter, offset, limit, sortBy).asSeq[E]
  }

  def getById(id: Id): Future[E] = {
    val filter = primaryKey $eq id
    log.trace(s"DAO.getById [$primaryKey] : $filter")
    internalFindBy(filter, 0, 1).asObj[E]
  }

  def findById(id: Id): Future[Option[E]] = {
    val filter = primaryKey $eq id
    log.trace(s"DAO.findById [$primaryKey] : $filter")
    internalFindBy(filter, 0, 1).asOpt[E]
  }

  // JSON fields can have different order, so if Id type is object don't use this query.
  // find({"id": { $in: [ {a: 1, b: 2 }, {a: 3, b: 4 }, ...] } }) - order of 'a' and 'b' fields may change
  // find({"id": { $in: [ {"id.a": 1, "id.b": 2}, ... ] } }) - will not work
  def findByIdsIn(ids: Seq[Id], offset: Int = 0, limit: Int = 0, sortBy: Bson = defaultSortBy): Future[Seq[E]] = ids match {
    case Nil => Future.successful(Seq.empty)
    case id :: Nil => findById(id).map(_.toSeq)
    case _ => internalFindBy(primaryKey $in (ids: _*), offset, limit, sortBy).asSeq[E]
  }

  def findByIdsOr(ids: Seq[Id], offset: Int = 0, limit: Int = 0, sortBy: Bson = defaultSortBy): Future[Seq[E]] = ids match {
    case Nil => Future.successful(Seq.empty)
    case id :: Nil => findById(id).map(_.toSeq)
    case _ => internalFindBy($or(ids.map(_.asJsonExpanded(primaryKey)): _*), offset, limit, sortBy).asSeq[E]
  }

  def findAll(offset: Int = 0, limit: Int = 0, sortBy: Bson = defaultSortBy): Future[Seq[E]] = {
    findBy(Document.empty, offset, limit, sortBy)
  }

  // ********************************************************************************
  // https://docs.mongodb.com/manual/reference/method/db.collection.findOneAndUpdate/
  // ********************************************************************************

  protected def internalUpdateBy(filter: Bson, update: Bson, upsert: Boolean = false): SingleObservable[Document] = {
    log.trace(s"DAO.internalUpdateBy [$primaryKey] : $filter")
    // By default "ReturnDocument.BEFORE" property used and returns the document before the update
    // val option = FindOneAndUpdateOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
    val option = FindOneAndUpdateOptions().upsert(upsert)
    collection.findOneAndUpdate(filter, update, option)
  }

  def updateById(id: Id, e: Document, upsert: Boolean = false): Future[Option[E]] = {
    val filter = primaryKey $eq id
    log.trace(s"DAO.updateById [$primaryKey] : $filter")
    internalUpdateBy(filter, e, upsert).asOpt[E]
  }

  def updateBy(filter: Bson, e: Document, upsert: Boolean = false): Future[Option[E]] = {
    log.trace(s"DAO.updateBy [$primaryKey] : $filter")
    internalUpdateBy(filter, e, upsert).asOpt[E]
  }


  // ********************************************************************************
  // https://docs.mongodb.com/manual/reference/method/db.collection.findOneAndReplace/
  // ********************************************************************************

  protected def internalReplaceBy(filter: Bson, replacement: Document, upsert: Boolean = false): SingleObservable[Document] = {
    log.trace(s"DAO.internalReplaceBy : $filter")
    // By default "ReturnDocument.BEFORE" property used and returns the document before the update
    // val option = FindOneAndReplaceOptions().upsert(true).returnDocument(ReturnDocument.AFTER)
    val option = FindOneAndReplaceOptions().upsert(upsert)
    collection.findOneAndReplace(filter, replacement, option)
  }

  def replaceById(id: Id, e: E, upsert: Boolean = false): Future[Option[E]] = {
    val filter = primaryKey $eq id
    internalReplaceBy(filter, e.toJson.skipNull(skipNull), upsert).asOpt[E]
  }

  def createOrReplaceById(id: Id, e: E): Future[Option[E]] = {
    replaceById(id, e, upsert = true)
  }

  /**
    * NOT ATOMICALLY find a document and replace it.
    * Impossible to upsert:true with a Dotted _id Query
    * https://docs.mongodb.com/manual/reference/method/db.collection.update/#upsert-true-with-a-dotted-id-query
    * @param id primary key filter
    * @param e entity to replace
    * @return None if document was created and Some(previous document) if the document was updated
    */
  def replaceOrInsertById(id: Id, e: E): Future[Option[E]] = {
    replaceById(id, e /* upsert = false */).flatMap {
      case beforeOpt @ Some(_) /* replaced */ => Future.successful(beforeOpt)
      case None => insert(e).map { _: Completed => None }
    }
  }

  def replaceBy(filter: Bson, e: E, upsert: Boolean = false): Future[Option[E]] = {
    internalReplaceBy(filter, e.toJson.skipNull(skipNull), upsert).asOpt[E]
  }

  def createOrReplaceBy(filter: Bson, e: E): Future[Option[E]] = {
    replaceBy(filter, e, upsert = true)
  }

  def distinct[T](fieldName: String, filter: Bson)(implicit ct: ClassTag[T]): Future[Seq[T]] = {
    collection.distinct[T](fieldName, filter).toFuture()
  }

  def aggregate(pipeline: Seq[Bson]): Future[Seq[Document]] = {
    collection.aggregate(pipeline).toFuture()
  }

  def deleteById(id: Id): Future[E] = ???
  def deleteByIds(id: Seq[Id]): Future[E] = ???

}
