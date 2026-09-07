import sbt._

object Dependencies {
  // Version constants
  lazy val scalapbVersion = "0.11.3"
  lazy val sparkVersion = BuildProfile.sparkVersion
  lazy val grpcJavaVersion = "1.37.0"
  lazy val parquetHadoopVersion = BuildProfile.parquetHadoopVersion
  lazy val parquetAvroVersion = BuildProfile.parquetAvroVersion
  lazy val hadoopVersion = BuildProfile.hadoopVersion
  lazy val jacksonVersion = BuildProfile.jacksonVersion

  lazy val munit = "org.scalameta" %% "munit" % "0.7.29"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.15"
  lazy val scalaCollectionCompat =
    "org.scala-lang.modules" %% "scala-collection-compat" % "2.11.0" % "provided,test"
  lazy val grpcNetty =
    "io.grpc" % "grpc-netty-shaded" % grpcJavaVersion excludeAll ExclusionRule(
      organization = "org.slf4j"
    )
  lazy val scalapbRuntime =
    "com.thesamet.scalapb" %% "scalapb-runtime" % scalapbVersion
  lazy val scalapbRuntimeGrpc =
    "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapbVersion
  lazy val scalapbCompilerPlugin =
    "com.thesamet.scalapb" %% "compilerplugin" % scalapbVersion
  lazy val sparkCore =
    "org.apache.spark" %% "spark-core" % sparkVersion % "provided,test" excludeAll (
      ExclusionRule(organization = "org.apache.arrow")
    )
  lazy val sparkSql =
    "org.apache.spark" %% "spark-sql" % sparkVersion % "provided,test" excludeAll (
      ExclusionRule(organization = "org.apache.arrow")
    )
  lazy val sparkCatalyst =
    "org.apache.spark" %% "spark-catalyst" % sparkVersion % "provided,test" excludeAll (
      ExclusionRule(organization = "org.apache.arrow")
    )
  lazy val sparkMLlib =
    "org.apache.spark" %% "spark-mllib" % sparkVersion % "provided,test" excludeAll (
      ExclusionRule(organization = "org.apache.arrow")
    )
  // Hadoop and Parquet are supplied by the selected Spark runtime. Keep them
  // out of the assembly to avoid duplicate classes and classloader conflicts.
  lazy val parquetHadoop =
    "org.apache.parquet" % "parquet-hadoop" % parquetHadoopVersion % "provided,test"
  // parquet-avro gives us AvroParquetWriter which supports withExtraMetaData
  // for emitting the `storage_version` / `group_field_id_list` kv-metadata
  // that milvus's StorageV2 packed-parquet format expects on backfilled
  // binlog files.
  //
  // Spark runtimes do not ship parquet-avro, so bundle only that module and
  // link it against the Parquet/Avro versions supplied by the active profile.
  lazy val parquetAvro =
    if (BuildProfile.isDatabricks154) {
      "org.apache.parquet" % "parquet-avro" % parquetAvroVersion excludeAll (
        ExclusionRule(organization = "org.apache.parquet"),
        ExclusionRule(organization = "org.apache.avro"),
        ExclusionRule(organization = "org.slf4j")
      )
    } else {
      "org.apache.parquet" % "parquet-avro" % parquetAvroVersion
    }
  // Avro is supplied by Spark. Test scope keeps it available to local tests.
  lazy val avroVersion = BuildProfile.avroVersion
  lazy val avro =
    "org.apache.avro" % "avro" % avroVersion % "provided,test"
  lazy val hadoopCommon =
    "org.apache.hadoop" % "hadoop-common" % hadoopVersion % "provided,test" exclude ("javax.activation", "activation")
  lazy val hadoopAws =
    "org.apache.hadoop" % "hadoop-aws" % hadoopVersion % "provided,test" exclude ("software.amazon.awssdk", "bundle")
  lazy val hadoopAliyun =
    "org.apache.hadoop" % "hadoop-aliyun" % hadoopVersion % "provided,test"
  lazy val awsSdkS3 =
    "software.amazon.awssdk" % "s3" % "2.30.38" // doc: https://javadoc.io/doc/software.amazon.awssdk/s3/2.30.38/index.html
  lazy val awsSdkS3Transfer =
    "software.amazon.awssdk" % "s3-transfer-manager" % "2.30.38"
  lazy val awsSdkCore =
    "com.amazonaws" % "aws-java-sdk-core" % "1.12.780"
  lazy val jacksonScala =
    runtimeProvidedOnDatabricks(
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion
    )
  lazy val jacksonDatabind =
    runtimeProvidedOnDatabricks(
      "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion
    )

  // Arrow dependencies for milvus-storage JNI
  lazy val arrowVersion = BuildProfile.arrowVersion
  lazy val arrowFormat = runtimeProvidedOnDatabricks(
    "org.apache.arrow" % "arrow-format" % arrowVersion
  )
  lazy val arrowVector = runtimeProvidedOnDatabricks(
    "org.apache.arrow" % "arrow-vector" % arrowVersion
  )
  lazy val arrowMemoryCore = runtimeProvidedOnDatabricks(
    "org.apache.arrow" % "arrow-memory-core" % arrowVersion
  )
  lazy val arrowMemoryNetty = runtimeProvidedOnDatabricks(
    "org.apache.arrow" % "arrow-memory-netty" % arrowVersion
  )
  lazy val arrowCData =
    if (BuildProfile.isDatabricks154) {
      "org.apache.arrow" % "arrow-c-data" % arrowVersion excludeAll (
        ExclusionRule(organization = "org.apache.arrow"),
        ExclusionRule(organization = "org.slf4j")
      )
    } else {
      "org.apache.arrow" % "arrow-c-data" % arrowVersion
    }

  lazy val profileDependencies: Seq[ModuleID] =
    if (BuildProfile.isDatabricks154) Seq(scalaCollectionCompat) else Seq.empty

  lazy val awsSdkDependencies: Seq[ModuleID] =
    if (BuildProfile.isDatabricks154) Seq.empty
    else Seq(awsSdkS3, awsSdkS3Transfer, awsSdkCore)

  lazy val profileDependencyOverrides: Seq[ModuleID] =
    if (BuildProfile.isDatabricks154) {
      Seq(
        "org.scala-lang.modules" %% "scala-collection-compat" % "2.11.0",
        "com.fasterxml.jackson.core" % "jackson-annotations" % jacksonVersion,
        "com.fasterxml.jackson.core" % "jackson-core" % jacksonVersion,
        "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion,
        "com.fasterxml.jackson.dataformat" % "jackson-dataformat-cbor" % jacksonVersion,
        "com.fasterxml.jackson.datatype" % "jackson-datatype-jsr310" % jacksonVersion,
        "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion
      )
    } else Seq.empty

  private def runtimeProvidedOnDatabricks(module: ModuleID): ModuleID =
    if (BuildProfile.isDatabricks154) module % "provided,test" else module
}
