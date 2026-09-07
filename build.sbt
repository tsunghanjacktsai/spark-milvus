import scala.io.Source
import scala.sys.process.Process

import xerial.sbt.Sonatype._

ThisBuild / sonatypeCredentialHost := sonatypeCentralHost
import Dependencies._

// Load Sonatype Central credentials
credentials += {
  val credFile = Path.userHome / ".sbt" / "sonatype_central_credentials"
  if (credFile.exists) {
    val lines = Source.fromFile(credFile).getLines().toList
    val props = lines
      .map { line =>
        val parts = line.split("=", 2)
        if (parts.length == 2) Some(parts(0).trim -> parts(1).trim) else None
      }
      .flatten
      .toMap

    Credentials(
      "Sonatype Nexus Repository Manager",
      props.getOrElse("host", "central.sonatype.com"),
      props.getOrElse("user", ""),
      props.getOrElse("password", "")
    )
  } else {
    Credentials(Path.userHome / ".sbt" / "sonatype.credentials")
  }
}

ThisBuild / organizationName := "zilliz"
ThisBuild / organizationHomepage := Some(url("https://zilliz.com/"))
ThisBuild / scalaVersion := BuildProfile.scalaVersion
ThisBuild / description := "Milvus Spark Connector to use in Spark ETLs to populate a Milvus vector database."
ThisBuild / versionScheme := Some("early-semver")

lazy val javaSpecificationVersion = System.getProperty(
  "java.specification.version",
  ""
)
lazy val databricksJavacOptions =
  if (!BuildProfile.isDatabricks154) Seq.empty
  else if (javaSpecificationVersion == "1.8")
    Seq("-source", "8", "-target", "8")
  else Seq("--release", "8")
lazy val databricksScalacOptions =
  if (!BuildProfile.isDatabricks154) Seq.empty
  else if (javaSpecificationVersion == "1.8") Seq("-target:jvm-1.8")
  else Seq("-release:8")
lazy val moduleOpenOptions =
  if (BuildProfile.isDatabricks154) Seq.empty
  else
    Seq(
      "--add-opens=java.base/java.nio=ALL-UNNAMED",
      "--add-opens=java.base/java.lang=ALL-UNNAMED",
      "--add-opens=java.base/java.lang.invoke=ALL-UNNAMED",
      "--add-opens=java.base/java.util=ALL-UNNAMED",
      "--add-opens=java.base/sun.security.action=ALL-UNNAMED"
    )

def nativeLibraryDirectory(base: File): File = {
  val nativeRoot = base / "src" / "main" / "resources" / "native"
  if (BuildProfile.isDatabricks154) nativeRoot / "linux-x86_64"
  else nativeRoot
}

// Remove all additional repository other than Maven Central from POM
ThisBuild / pomIncludeRepository := { _ => false }
ThisBuild / publishMavenStyle := true

ThisBuild / publishTo := {
  val centralSnapshots =
    "https://central.sonatype.com/repository/maven-snapshots/"
  if (isSnapshot.value) Some("central-snapshots" at centralSnapshots)
  else localStaging.value
}

ThisBuild / licenses := List(
  "Server Side Public License v1" -> new URL(
    "https://raw.githubusercontent.com/mongodb/mongo/refs/heads/master/LICENSE-Community.txt"
  ),
  "GNU Affero General Public License v3 (AGPLv3)" -> new URL(
    "https://www.gnu.org/licenses/agpl-3.0.txt"
  )
)
ThisBuild / homepage := Some(
  url("https://github.com/zilliztech/milvus-spark-connector")
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    url("https://github.com/zilliztech/milvus-spark-connector"),
    "scm:git@github.com:zilliztech/milvus-spark-connector.git"
  )
)
ThisBuild / developers := List(
  Developer(
    id = "santiago-wjq",
    name = "Santiago Wu",
    email = "santiago.wu@zilliz.com",
    url = url("https://github.com/santiago-wjq")
  )
)

lazy val arch = System.getProperty("os.arch") match {
  case "amd64" | "x86_64"  => "amd64"
  case "aarch64" | "arm64" => "arm64"
  case other               => other
}

// Get git branch name from env var (for Docker builds) or git command, sanitize for Maven version
lazy val gitBranch = {
  val branch = sys.env.getOrElse(
    "GIT_BRANCH",
    scala.util
      .Try(Process("git rev-parse --abbrev-ref HEAD").!!.trim)
      .getOrElse("unknown")
  )
  // Replace invalid characters for Maven version (only alphanumeric, dash, dot, underscore allowed)
  branch.replaceAll("[^a-zA-Z0-9._-]", "-")
}

lazy val root = (project in file("."))
  .settings(
    name := "spark-connector",
    assembly / parallelExecution := true,
    assembly / assemblyPackageScala / assembleArtifact := false,
    Test / parallelExecution := true,
    Compile / compile / parallelExecution := true,
    version := s"${gitBranch}-${arch}-SNAPSHOT",
    organization := "com.zilliz",
    Compile / javacOptions ++= databricksJavacOptions,
    Compile / scalacOptions ++= databricksScalacOptions,

    // Disable Scaladoc and sources jar for publish (not needed, speeds up build)
    Compile / packageDoc / publishArtifact := false,
    Compile / packageSrc / publishArtifact := false,

    // Fork JVM for run and tests to properly load native libraries
    run / fork := true,
    Test / fork := true,

    // Show test logs immediately (don't buffer)
    Test / logBuffered := false,

    // Test timeout - 10 seconds per test to avoid hanging
    Test / testOptions += Tests.Argument(
      TestFrameworks.ScalaTest,
      "-oDF",
      "-W",
      "10",
      "10"
    ),

    // JVM options for run
    run / javaOptions ++= Seq(
      "-Xss2m",
      "-Djava.library.path=."
    ) ++ moduleOpenOptions.take(1),
    run / envVars := Map(
      "LD_PRELOAD" -> (nativeLibraryDirectory(
        baseDirectory.value
      ) / "libmilvus-storage.so").getAbsolutePath
    ),

    // Include test dependencies in run classpath for example applications
    Compile / run / fullClasspath := (Compile / run / fullClasspath).value ++ (Test / fullClasspath).value,

    // JVM options for tests
    Test / javaOptions ++= Seq(
      "-Xss2m",
      "-Xmx4g",
      s"-Djava.library.path=${nativeLibraryDirectory(baseDirectory.value).getAbsolutePath}",
      "-Dlog4j2.configurationFile=log4j2.properties",
      "-Dlog4j2.debug=false"
    ) ++ moduleOpenOptions,
    Test / envVars := Map(
      "LD_LIBRARY_PATH" -> nativeLibraryDirectory(
        baseDirectory.value
      ).getAbsolutePath
    ),

    // DBR 15.4 uses Scala 2.12, so compile the pinned JNI binding sources with
    // the connector instead of consuming the default Scala 2.13 binding JAR.
    Compile / unmanagedSourceDirectories ++= {
      if (BuildProfile.isDatabricks154) {
        Seq(
          baseDirectory.value / "milvus-storage" / "java" / "src" / "main" / "scala",
          baseDirectory.value / "milvus-storage" / "java" / "src" / "main" / "java"
        )
      } else Seq.empty
    },
    Compile / unmanagedJars ++= {
      if (BuildProfile.isDatabricks154) Seq.empty
      else {
        val scalaBinary = scalaBinaryVersion.value
        Seq(
          baseDirectory.value / "milvus-storage" / "java" / "target" / s"scala-$scalaBinary" / s"milvus-storage-jni_$scalaBinary-0.1.0-SNAPSHOT.jar"
        )
      }
    },
    Test / unmanagedJars ++= (Compile / unmanagedJars).value,

    // 老 log binding (slf4j-log4j12 / reload4j / log4j 1.x) 与 spark 的 log4j2 冲突，
    // 全局排除掉。
    excludeDependencies ++= Seq(
      ExclusionRule("org.slf4j", "slf4j-log4j12"),
      ExclusionRule("org.slf4j", "slf4j-reload4j"),
      ExclusionRule("log4j", "log4j"),
      ExclusionRule("ch.qos.reload4j", "reload4j")
    ),
    // 在 assembly 阶段过滤掉 slf4j-api jar：
    // 编译时仍可用（来自传递依赖），但不进 fat jar，运行时由 spark 镜像
    // /opt/spark/jars/slf4j-api-2.x.jar 提供，避免 userClassPathFirst=true 时
    // Logger 被加载两份触发 LinkageError
    assembly / assemblyExcludedJars := {
      val cp = (assembly / fullClasspath).value
      cp.filter { f =>
        val n = f.data.getName
        n.startsWith("slf4j-api-") ||
        (BuildProfile.isDatabricks154 && n.startsWith(
          "scala-collection-compat_2.12-"
        ))
      }
    },
    libraryDependencies ++= Seq(
      munit % Test,
      scalaTest % Test,
      grpcNetty,
      scalapbRuntime % "protobuf",
      scalapbRuntimeGrpc,
      scalapbCompilerPlugin,
      sparkCore,
      sparkSql,
      sparkCatalyst,
      sparkMLlib,
      parquetHadoop,
      parquetAvro,
      avro,
      hadoopCommon,
      hadoopAws,
      hadoopAliyun,
      jacksonScala,
      jacksonDatabind,
      arrowFormat,
      arrowVector,
      arrowMemoryCore,
      arrowMemoryNetty,
      arrowCData
    ) ++ profileDependencies ++ awsSdkDependencies,
    dependencyOverrides ++= profileDependencyOverrides,
    Compile / PB.protoSources += baseDirectory.value / "milvus-proto/proto",
    Compile / PB.targets := Seq(
      scalapb.gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb"
    ),
    Compile / unmanagedSourceDirectories += (
      Compile / PB.targets
    ).value.head.outputPath,
    Compile / packageBin / mappings ++= {
      val base = (Compile / PB.targets).value.head.outputPath
      (base ** "*.scala").get.map { file =>
        file -> s"generated_protobuf/${file.relativeTo(base).getOrElse(file)}"
      }
    },
    Compile / resourceDirectories += baseDirectory.value / "src" / "main" / "resources",
    // 发布 assembly JAR 作为单独的 artifact，带 classifier
    assembly / artifact := {
      val art = (assembly / artifact).value
      art.withClassifier(Some("assembly"))
    },
    addArtifact(assembly / artifact, assembly)
  )

assembly / assemblyShadeRules := Seq(
  ShadeRule.rename("com.google.protobuf.**" -> "shade_proto.@1").inAll,
  ShadeRule.rename("com.google.common.**" -> "shade_googlecommon.@1").inAll
  // Note: Arrow cannot be shaded due to JNI bindings with hardcoded class names
  // Use spark.driver.userClassPathFirst=true to prioritize our Arrow version
)

assembly / assemblyMergeStrategy := {
  case PathList("native", xs @ _*) => MergeStrategy.first
  // Handle all Netty native-image files
  case PathList("META-INF", "native-image", "io.netty", _*) =>
    MergeStrategy.discard
  // Handle Netty version properties
  case PathList("META-INF", "io.netty.versions.properties") =>
    MergeStrategy.discard
  // Handle mime.types
  case PathList("mime.types") =>
    MergeStrategy.filterDistinctLines
  // Handle FastDoubleParser notice
  case PathList("META-INF", "FastDoubleParser-NOTICE") =>
    MergeStrategy.discard
  // Handle Arrow git properties
  case PathList("arrow-git.properties") =>
    MergeStrategy.first
  // Handle module-info.class files
  case x if x.endsWith("module-info.class") =>
    MergeStrategy.discard
  // Handle hadoop package-info conflicts
  case PathList("org", "apache", "hadoop", xs @ _*)
      if xs.last == "package-info.class" =>
    MergeStrategy.first
  // Handle AWS SDK VersionInfo conflicts
  case PathList("software", "amazon", "awssdk", xs @ _*)
      if xs.last == "VersionInfo.class" =>
    MergeStrategy.first
  // Default case
  case x =>
    val oldStrategy = (ThisBuild / assemblyMergeStrategy).value
    oldStrategy(x)
}

// import scalapb.compiler.Version
// val grpcJavaVersion =
//   SettingKey[String]("grpcJavaVersion", "ScalaPB gRPC Java version")
// grpcJavaVersion := Version.grpcJavaVersion

// See https://www.scala-sbt.org/1.x/docs/Using-Sonatype.html for instructions on how to publish to Sonatype.
