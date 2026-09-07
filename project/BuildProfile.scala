object BuildProfile {
  val Default = "default"
  val Databricks154 = "databricks-15.4"

  private val supported = Set(Default, Databricks154)

  val name: String = sys.props
    .get("spark.milvus.build.profile")
    .orElse(sys.env.get("SPARK_MILVUS_BUILD_PROFILE"))
    .getOrElse(Default)

  require(
    supported.contains(name),
    s"Unsupported spark-milvus build profile '$name'. " +
      s"Supported profiles: ${supported.toSeq.sorted.mkString(", ")}"
  )

  val isDatabricks154: Boolean = name == Databricks154

  val scalaVersion: String = if (isDatabricks154) "2.12.15" else "2.13.16"
  val sparkVersion: String = if (isDatabricks154) "3.5.0" else "4.0.0"
  val hadoopVersion: String = if (isDatabricks154) "3.3.6" else "3.4.1"
  val parquetHadoopVersion: String = "1.13.1"
  val parquetAvroVersion: String =
    if (isDatabricks154) "1.13.1" else "1.15.2"
  val jacksonVersion: String = if (isDatabricks154) "2.15.2" else "2.17.3"
  val arrowVersion: String = if (isDatabricks154) "15.0.0" else "17.0.0"
  val avroVersion: String = if (isDatabricks154) "1.11.3" else "1.12.0"
}
