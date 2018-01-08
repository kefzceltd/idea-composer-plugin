package org.psliwa.idea.composerJson.composer.command

import com.intellij.execution.ExecutionException
import com.intellij.execution.process.{OSProcessManager, ProcessAdapter, ProcessEvent, ScriptRunnerUtil}
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.Task.Backgroundable
import com.intellij.openapi.progress.{PerformInBackgroundOption, ProgressIndicator, ProgressManager}
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import org.psliwa.idea.composerJson
import org.psliwa.idea.composerJson.ComposerBundle
import org.psliwa.idea.composerJson.composer.ComposerPackage
import org.psliwa.idea.composerJson.intellij.Notifications
import org.psliwa.idea.composerJson.ui.OutputDialog

trait PackagesInstaller {
  def install(packages: List[ComposerPackage]): Unit
}

class DefaultPackagesInstaller(project: Project, config: Configuration, file: PsiFile) extends PackagesInstaller {

  override def install(packages: List[ComposerPackage]): Unit = {
    val task = new Backgroundable(project, ComposerBundle.message("inspection.notInstalledPackage.installing"), true, PerformInBackgroundOption.DEAF) {
      override def run(indicator: ProgressIndicator): Unit = {
        val packageNames = packages.map(_.name).mkString(", ")

        doInstall(indicator) match {
          case Left(message) => {
            val installationFailed = ComposerBundle.message(
              "inspection.notInstalledPackage.errorTitle",
              packages.map(pkg => pkg.name+" ("+pkg.version+")").mkString(", ")
            )

            ApplicationManager.getApplication.invokeLater(() => {
              new OutputDialog(installationFailed, message).setVisible(true)
            })

            Notifications.error(installationFailed, message, Some(project))
          }
          case Right(message) => {
            //refresh parent directory and composer.lock file in order to inter alia reanalyze composer.json
            val parentDir = file.getVirtualFile.getParent
            parentDir.refresh(true, false)
            Option(parentDir.findChild("vendor")).foreach(_.refresh(true, true))
            Option(parentDir.findChild(composerJson.ComposerLock)).foreach(_.refresh(true, false))

            Notifications.info(
              ComposerBundle.message("inspection.notInstalledPackage.successTitle"),
              ComposerBundle.message("inspection.notInstalledPackage.success", packageNames),
              Some(project)
            )
          }
        }
      }

      private def doInstall(indicator: ProgressIndicator): Either[String, String] = {
        indicator.setIndeterminate(true)

        val packagesParams = packages.map(pkg => pkg.name)
        val commandParams = (config.commandOptions ++ List("update") ++ config.composerOptions ++ packagesParams).toArray
        indicator.setText("composer update "+packagesParams.mkString(" "))

        val message = new StringBuilder()

        try {
          val handler = ScriptRunnerUtil.execute(
            config.executable,
            file.getVirtualFile.getParent.getPath,
            null,
            commandParams
          )

          handler.addProcessListener(new ProcessAdapter {
            override def onTextAvailable(event: ProcessEvent, outputType: Key[_]): Unit = {
              indicator.setText2(event.getText)
              message
                .append("\n")
                .append(event.getText)
            }
          })

          handler.startNotify()

          var finished = false
          while(!finished) {
            finished = handler.waitFor(1000L)

            if(indicator.isCanceled) {
              OSProcessManager.getInstance().killProcessTree(handler.getProcess)
              finished = true
            }
          }

          if(!indicator.isCanceled && handler.getProcess.exitValue() != 0) {
            Left(message.toString())
          } else {
            Right(message.toString())
          }
        } catch {
          case e: ExecutionException =>
            import scala.compat.Platform.EOL
            Left(e.toString+": "+e.getMessage+"\n\n"+e.getStackTrace.mkString("", EOL, EOL))
        }
      }
    }

    ProgressManager.getInstance().run(task)
  }
}
