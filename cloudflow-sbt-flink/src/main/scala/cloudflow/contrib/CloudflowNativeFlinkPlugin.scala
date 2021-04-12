/*
 * Copyright (C) 2016-2021 Lightbend Inc. <https://www.lightbend.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cloudflow.contrib

import sbt._
import sbt.Keys._
import sbtdocker._
import sbtdocker.DockerKeys._
import sbtdocker.Instructions
import sbtdocker.staging.CopyFile
import com.typesafe.sbt.packager.Keys._

object CloudflowNativeFlinkPlugin extends AutoPlugin {
  val FlinkHome = "/opt/flink"
  val FlinkUsrLib = s"$FlinkHome/usrlib"

  val AppJarsDir: String = "app-jars"
  val DepJarsDir: String = "dep-jars"
  val UserInImage = "185" // default non-root user in the spark image
  val userAsOwner: String => String = usr => s"$usr:cloudflow"

  val contribVersion = buildinfo.BuildInfo.version

  object autoImport {
    val flinkNativeCloudflowDeps = settingKey[Seq[ModuleID]]("Flink Native dependencies")
    val flinkNativeCloudflowDockerInstructions =
      taskKey[Seq[sbtdocker.Instruction]]("Docker instructions to build the Cloudflow Flink Native Streamlet")
  }

  import autoImport._

  override def trigger = noTrigger

  override lazy val projectSettings = Seq(
    flinkNativeCloudflowDeps :=
      Seq(
        "com.lightbend.cloudflow.contrib" %% "cloudflow-flink" % contribVersion,
        "com.lightbend.cloudflow.contrib" %% "cloudflow-flink-testkit" % contribVersion % "test"),
    flinkNativeCloudflowDockerInstructions := {
      val appDir: File = stage.value
      val appJarsDir: File = new File(appDir, AppJarsDir)
      val depJarsDir: File = new File(appDir, DepJarsDir)

      val flinkEntrypoint = (ThisProject / target).value / "cloudflow" / "flink" / "native" / "docker-entrypoint.sh"
      IO.write(flinkEntrypoint, flinkEntrypointContent)

      val scalaVersion = (ThisProject / scalaBinaryVersion).value
      val flinkVersion = "1.13.0"
      val flinkPackageVersion = "1.13.0-rc0"

      val flinkTgz = s"flink-$flinkVersion-bin-scala_2.12.tgz"

      val flinkTgzUrl = s"https://dist.apache.org/repos/dist/dev/flink/flink-${flinkPackageVersion}/$flinkTgz"

      Seq(
        Instructions.Env("FLINK_VERSION", flinkVersion),
        Instructions.Env("SCALA_VERSION", scalaVersion),
        Instructions.Env("FLINK_HOME", FlinkHome),
        Instructions.Env("PATH", "$FLINK_HOME/bin:$PATH"),
        Instructions.Env("FLINK_ENV_JAVA_OPTS", "-Dlogback.configurationFile=/opt/logging/logback.xml"),
        Instructions.User("root"),
        Instructions.Copy(CopyFile(flinkEntrypoint), "/docker-entrypoint.sh"),
        Instructions.Run.shell(
          Seq(
            Seq("apk", "add", "curl", "wget", "bash", "snappy-dev", "gettext-dev"),
            Seq("wget", flinkTgzUrl),
            Seq("tar", "-xvzf", flinkTgz),
            Seq("mv", s"flink-${flinkVersion}", FlinkHome),
            Seq("rm", flinkTgz),
            Seq("addgroup", "-S", "-g", "9999", "flink"),
            Seq("adduser", "-S", "-h", FlinkHome, "-u", "9999", "flink", "flink"),
            Seq("addgroup", "-S", "-g", "185", "cloudflow"),
            Seq("adduser", "-u", "185", "-S", "-h", "/home/cloudflow", "-s", "/sbin/nologin", "cloudflow", "root"),
            Seq("adduser", "cloudflow", "cloudflow"),
            Seq("rm", "-rf", "/var/lib/apt/lists/*"),
            Seq("chown", "-R", "flink:flink", "/var"),
            Seq("chown", "-R", "flink:root", "/usr/local"),
            Seq("chmod", "775", "/usr/local"),
            Seq("mkdir", FlinkUsrLib),
            Seq(
              "mv",
              s"${FlinkHome}/opt/flink-queryable-state-runtime_${scalaVersion}-${flinkVersion}.jar",
              s"${FlinkHome}/lib"),
            Seq("mkdir", "-p", "/prometheus"),
            Seq(
              "curl",
              "https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/0.11.0/jmx_prometheus_javaagent-0.11.0.jar",
              "-o",
              "/prometheus/jmx_prometheus_javaagent.jar"),
            Seq("chmod", "-R", "777", FlinkHome),
            Seq("chmod", "a+x", "/docker-entrypoint.sh")).reduce(_ ++ Seq("&&") ++ _)),
        Instructions.EntryPoint.exec(Seq("bash", "/docker-entrypoint.sh")),
        Instructions.User(UserInImage),
        Instructions.WorkDir(FlinkHome),
        Instructions
          .Copy(sources = Seq(CopyFile(depJarsDir)), destination = FlinkUsrLib, chown = Some(userAsOwner(UserInImage))),
        Instructions
          .Copy(sources = Seq(CopyFile(appJarsDir)), destination = FlinkUsrLib, chown = Some(userAsOwner(UserInImage))),
        Instructions.Run(
          s"cp ${FlinkUsrLib}/cloudflow-runner_${(ThisProject / scalaBinaryVersion).value}*.jar  $FlinkUsrLib/cloudflow-runner.jar"),
        Instructions.Cmd("help"),
        Instructions.Expose(Seq(6123, 8081)))
    })

  private lazy val flinkEntrypointContent =
    scala.io.Source
      .fromResource("docker-entrypoint.sh", getClass().getClassLoader())
      .getLines
      .mkString("\n")

}