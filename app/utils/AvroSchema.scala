package utils

import java.io.ByteArrayInputStream

import org.apache.avro.Schema.Type._
import org.apache.avro.compiler.idl.Idl
import org.apache.avro.{Schema, SchemaBuilder}
import play.api.libs.json._

import scala.jdk.CollectionConverters._
import scala.util.matching.Regex

/**
 * Future features, add categorization (ENUM) for values less than 10
 */
object AvroSchema {

  val floatingPointPattern: Regex = "[-+]?[0-9]*\\.[0-9]+".r

  /**
   * Create an Avro schema from a JSON object. It only accepts an object or an array of objects.
   * In any cases, the output is the Avro schema of a record.
   * The "namespace" would be empty. In order to apply it, use [[utils.AvroSchema#apply(json.JsValue, String)]] method.
   * It the input is an array of objects, then generated schema will try to match with all the objects.
   * Here are the rules to apply:
   *
   * @param json the input JSON
   * @param name the name of the record
   * @return the Avro schema of the record
   * @throws IllegalArgumentException if the JSON is not an object or an array of objects
   */
  def apply(json: JsValue, name: String): Schema = json match {
    case jsValue: JsObject => createRecord(jsValue, name)
    case jsValue: JsArray if jsValue.value.head.isInstanceOf[JsObject] =>
      jsValue.value
        .foldLeft(createDummyRecordSchema(name)) { (schema, aRecord) =>
          mergeRecordSchema(schema, createRecord(aRecord.as[JsObject], name))
        }
    case _ => throw new IllegalArgumentException("The input message should be a JSON object or a JSON array")
  }

  def apply(json: JsValue, name: String, nameSpace: String): Schema = json match {
    case jsValue: JsObject => createRecord(jsValue, name, Some(nameSpace))
    case jsValue: JsArray if jsValue.value.head.isInstanceOf[JsObject] =>
      jsValue.value
        .foldLeft(createDummyRecordSchema(name, Some(nameSpace))) { (schema, aRecord) =>
          mergeRecordSchema(schema, createRecord(aRecord.as[JsObject], name, Some(nameSpace)))
        }
    case _ => throw new IllegalArgumentException("The input message should be a JSON object or a JSON array")
  }

  def createSchema(json: JsValue, name: String, namespace: Option[String], isArrayItem: Boolean = false): Schema = {
    json match {
      case _: JsString => SchemaBuilder.builder().stringType().makeNullable
      case fieldValue: JsNumber if floatingPointPattern.matches(fieldValue.toString()) =>
        SchemaBuilder.builder().floatType().makeNullable
      case fieldValue: JsNumber if fieldValue.value.isValidInt => SchemaBuilder.builder().intType().makeNullable
      case _: JsNumber => SchemaBuilder.builder().longType().makeNullable
      case _: JsBoolean => SchemaBuilder.builder().booleanType().makeNullable
      case _: JsObject if isArrayItem && name.endsWith("s") => createRecord(json.as[JsObject], name.init, namespace).makeNullable
      case _: JsObject => createRecord(json.as[JsObject], name, namespace).makeNullable
      case _: JsArray => createArray(json.as[JsArray], name, namespace).makeNullable
      case _ => SchemaBuilder.builder().nullType()
    }
  }

  def createRecord(json: JsObject, name: String, nameSpace: Option[String] = None): Schema = {
    val builder = SchemaBuilder.record(s"${name.head.toUpper}${name.tail}").namespace(nameSpace.getOrElse("")).fields()
    json.fields
      .filter(_._1.nonEmpty)
      .map { case (fieldName, fieldValue) =>
        val (validFieldName, doc) = sanitizeFieldName(fieldName)
        (validFieldName, fieldValue, doc)
      }
      .sortBy(_._1)
      .foreach { case (fieldName, fieldValue, doc) =>
        val fieldSchema = createSchema(fieldValue, fieldName, nameSpace)
        builder.name(fieldName).doc(doc).`type`(fieldSchema).withDefault(null)
      }
    builder.endRecord()
  }

  def sanitizeFieldName(fieldName: String): (String, String) = {
    val sanitizedFieldName = fieldName.replaceAll("[^A-Za-z0-9_]", "_")
    if (sanitizedFieldName.equals(fieldName)) (fieldName, "")
    else (sanitizedFieldName, s"The original field name was '$fieldName' but some characters is not accepted in " +
      s"the field name of Avro record")
  }

  def createArray(json: JsArray, name: String, namespace: Option[String]): Schema = json.value.toList match {
    case Nil => SchemaBuilder.array().items(SchemaBuilder.builder().nullType())
    case arr => SchemaBuilder.array().items(createSchema(arr.head, name, namespace, isArrayItem = true))
  }

  private def createDummyRecordSchema(name: String, nameSpace: Option[String] = None): Schema =
    SchemaBuilder.record(name).namespace(nameSpace.getOrElse("")).fields().endRecord()

  /**
   * Merges the fields of the right side into the fields of the left side.
   * - If the field does not exist in the left, just add it
   *
   * @param left  the base record
   * @param right the new record to be merged with the base record
   * @return
   */
  private def mergeRecordSchema(left: Schema, right: Schema): Schema = {
    val leftKeyed: Seq[(String, Schema.Field)] = left.getFields.asScala.map(f => f.name() -> f).toSeq
    val rightKeyed: Seq[(String, Schema.Field)] = right.getFields.asScala.map(f => f.name() -> f).toSeq
    val builder = SchemaBuilder.record(left.getName).namespace(left.getNamespace).fields()
    val x: Map[String, Seq[(String, Schema.Field)]] = (leftKeyed ++ rightKeyed).groupBy(_._1)
    val y: Seq[(String, Seq[Schema.Field])] = x.map(a => (a._1, a._2.map(_._2))).toSeq.sortBy(_._1)
    y
      .foldLeft(builder) {
        /* Field is in the left not in the right */
        case (b, (fieldName, field :: Nil)) =>
          b.name(fieldName).`type`(field.schema()).withDefault(null)
        case (b, (fieldName, existingField :: newField :: Nil)) if haveSameSchema(newField, existingField) =>
          b.name(fieldName).`type`(existingField.schema()).withDefault(null)
        case (b, (fieldName, existingField :: newField :: Nil))
          if existingField.schema().getTypesWithoutNull.isRecord && newField.schema().getTypesWithoutNull.isRecord =>
          b
            .name(fieldName)
            .`type`(
              mergeRecordSchema(
                existingField.schema().getTypesWithoutNull,
                newField.schema().getTypesWithoutNull
              ).makeNullable
            )
            .withDefault(null)
        case (b, (fieldName, existingField :: newField :: Nil)) if existingField.schema().getTypesWithoutNull.isArray =>
          val itemSchema = existingField.schema().mergeWith(newField.schema()).makeNullable
          b.name(fieldName).`type`(itemSchema).withDefault(null)
        case (b, (fieldName, existingField :: newField :: Nil)) =>
          b
            .name(fieldName)
            .`type`(
              unionOfTwoFields(
                existingField.schema().getTypesWithoutNull,
                newField.schema().getTypesWithoutNull
              ).makeNullable
            )
            .withDefault(null)
      }
    builder.endRecord()
  }

  def unionOfTwoFields(left: Schema, right: Schema): Schema = {
    if (right.getType.equals(Schema.Type.NULL)) return unionOfTwoFields(right, left)
    if (!left.isUnion && !right.isUnion) {
      SchemaBuilder.unionOf().`type`(left).and().`type`(right).endUnion()
    } else if (left.isUnion && !right.isUnion) {
      unionOfUnionAndNonUnion(left, right)
    } else if (!left.isUnion && right.isUnion) {
      unionOfUnionAndNonUnion(right, left)
    } else {
      val types = (left.getTypes.asScala ++ right.getTypes.asScala).distinct.asJava
      Schema.createUnion(types)
    }
  }

  def unionOfUnionAndNonUnion(union: Schema, nonUnion: Schema): Schema = {
    val nonUnionAfterMerge = union.getTypes.asScala.filter(_.isRecord).filter(_.getFullName.equals(nonUnion.getFullName)).toList match {
      case Nil => nonUnion
      case matched :: Nil => mergeRecordSchema(matched, nonUnion)
    }
    val types = union.getTypes.asScala.filterNot(t => t.isRecord && t.getFullName.equals(nonUnion.getFullName)) :+ nonUnionAfterMerge
    Schema.createUnion(types.distinct.asJava)
  }

  def unionOfNonUnionAndUnion(nonUnion: Schema, union: Schema): Schema = {
    val nonUnionAfterMerge = union.getTypes.asScala.filter(_.isRecord).filter(_.getFullName.equals(nonUnion.getFullName)).toList match {
      case Nil => nonUnion
      case matched :: Nil => mergeRecordSchema(matched, nonUnion)
    }
    val types = Seq(nonUnionAfterMerge) ++ union.getTypes.asScala.filterNot(t => t.isRecord && t.getFullName.equals(nonUnion.getFullName))
    Schema.createUnion(types.asJava)
  }

  def haveSameSchema(left: Schema.Field, right: Schema.Field): Boolean =
    left.schema().getTypesWithoutNull.equals(right.schema().getTypesWithoutNull)

  def recordMatcher(schema: Schema)(fullName: String): Boolean = schema.isRecord && schema.getFullName.equals(fullName)

  /*  Schema implicits */
  implicit class SchemaImprovement(schema: Schema) {
    def getTypesWithoutNull: Schema = schema match {
      case s if s.isNullable && s.isUnion => // It could be just NULL type
        s.getTypes.asScala.filterNot(_.getType.equals(NULL)).toList match {
          case singleType :: Nil => singleType
          case multipleTypes => Schema.createUnion(multipleTypes.asJava)
        }
      case _ => schema
    }

    def mergeWith(otherSchema: Schema): Schema = (schema, otherSchema) match {
      // Same
      case (_, _) if schema.equals(otherSchema) => schema
      // Record with Record
      case (_, _) if schema.isRecord && otherSchema.isRecord => mergeRecordSchema(schema, otherSchema)
      // Record with a union of having same record
      case (_, _) if schema.isRecord && otherSchema.isUnion &&
        otherSchema.getTypes.asScala.exists(recordMatcher(_)(schema.getFullName)) =>
        val matchingRecord = otherSchema.getTypes.asScala.find(recordMatcher(_)(schema.getFullName)).get
        val unionWithOutMatchingRecord = Schema.createUnion(otherSchema.getTypes.asScala.filter(!recordMatcher(_)(schema.getFullName)).asJava)
        unionOfNonUnionAndUnion(schema.mergeWith(matchingRecord), unionWithOutMatchingRecord)
      // Record with a union of having same record
      case (_, _) if otherSchema.isRecord && schema.isUnion &&
        schema.getTypes.asScala.exists(recordMatcher(_)(otherSchema.getFullName)) =>
        val matchingRecord = schema.getTypes.asScala.find(recordMatcher(_)(otherSchema.getFullName)).get
        val unionWithOutMatchingRecord = Schema.createUnion(schema.getTypes.asScala.filter(!recordMatcher(_)(otherSchema.getFullName)).asJava)
        unionOfNonUnionAndUnion(otherSchema.mergeWith(matchingRecord), unionWithOutMatchingRecord)
      // nullable with nullable
      case (_, _) if schema.isUnion && schema.isNullable && otherSchema.isUnion && otherSchema.isNullable =>
        schema.getTypesWithoutNull.mergeWith(otherSchema.getTypesWithoutNull).makeNullable
      // null with nullable
      case (_, _) if !schema.isUnion && schema.isNullable && otherSchema.isUnion && otherSchema.isNullable => otherSchema
      // nullable with null
      case (_, _) if schema.isUnion && schema.isNullable && !otherSchema.isUnion && otherSchema.isNullable => schema
      // Array with nonArray and not nullable and not union
      case (_, _) if schema.isArray && !otherSchema.isArray && !otherSchema.isUnion && !otherSchema.isNullable =>
        SchemaBuilder.unionOf().`type`(schema).and().`type`(otherSchema).endUnion()
      case (_, _) if otherSchema.isArray && !schema.isArray && !schema.isUnion && !schema.isNullable =>
        SchemaBuilder.unionOf().`type`(schema).and().`type`(otherSchema).endUnion()
      // Array with nonArray and     nullable and not union
      case (_, _) if schema.isArray && !otherSchema.isArray && !otherSchema.isUnion && otherSchema.isNullable =>
        schema.makeNullable
      case (_, _) if otherSchema.isArray && !schema.isArray && !schema.isUnion && schema.isNullable =>
        otherSchema.makeNullable
      // Array with nonArray and     nullable and     union
      case (_, _) if schema.isArray && !otherSchema.isArray && otherSchema.isUnion && otherSchema.isNullable =>
        schema.mergeWith(otherSchema.getTypesWithoutNull).makeNullable
      case (_, _) if otherSchema.isArray && !schema.isArray && schema.isUnion && schema.isNullable =>
        schema.getTypesWithoutNull.mergeWith(otherSchema).makeNullable
      // Array with nonArray and not nullable and     union
      case (_, _) if schema.isArray && !otherSchema.isArray && otherSchema.isUnion && !otherSchema.isNullable =>
        unionOfNonUnionAndUnion(schema, otherSchema)
      case (_, _) if otherSchema.isArray && !schema.isArray && schema.isUnion && !schema.isNullable =>
        unionOfUnionAndNonUnion(schema, otherSchema)
      // Array with Array
      case (_, _) if schema.isArray && otherSchema.isArray =>
        SchemaBuilder.array().items(schema.getElementType.mergeWith(otherSchema.getElementType).makeNullable)
    }

    def makeNullable: Schema =
      if (schema.isNullable) schema
      else if (schema.isUnion) unionOfNonUnionAndUnion(SchemaBuilder.builder().nullType(), schema)
      else SchemaBuilder.builder().unionOf().nullType().and().`type`(schema).endUnion()

    def isRecord: Boolean = schema.getType.equals(RECORD)
    def isArray: Boolean = schema.getType.equals(Schema.Type.ARRAY)

    def toIdl(protocol: String): String = {
      if (!schema.isRecord) throw new IllegalArgumentException("The input schema is not a record")
      new AvroSchemaToIdl(schema, protocol).convert()
    }

  }

  def idlToSchema(in: String): String = {
    new Idl(new ByteArrayInputStream(in.getBytes)).CompilationUnit().toString(true)
  }
}
