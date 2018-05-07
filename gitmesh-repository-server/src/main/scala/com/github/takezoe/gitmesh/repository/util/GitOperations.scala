package com.github.takezoe.gitmesh.repository.util

import java.io.File

import com.github.takezoe.gitmesh.repository.util.syntax.{defining, using}
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.{Constants, RepositoryBuilder}

trait GitOperations {

  def gitCheckEmpty(repositoryName: String)(implicit config: Config): Boolean = {
    using(Git.open(new File(config.directory, repositoryName))){ git =>
      git.getRepository.resolve(Constants.HEAD) == null
    }
  }

  def gitInit(repositoryName: String)(implicit config: Config): Unit = {
    using(new RepositoryBuilder().setGitDir(new File(config.directory, repositoryName)).setBare.build){ repository =>
      repository.create(true)
      defining(repository.getConfig){ config =>
        config.setBoolean("http", null, "receivepack", true)
        config.save
      }
    }
  }

  // TODO Retry clone if failed
  def gitClone(repositoryName: String, sourceUrl: String)(implicit config: Config): Unit = {
    using(Git.cloneRepository().setBare(true).setURI(sourceUrl)
      .setDirectory(new File(config.directory, repositoryName)).setCloneAllBranches(true).call()){ git =>
      using(git.getRepository){ repository =>
        defining(repository.getConfig){ config =>
          config.setBoolean("http", null, "receivepack", true)
          config.save
        }
      }
    }
  }

  def gitPushAll(repositoryName: String, targetUrl: String)(implicit config: Config): Unit = {
    //Thread.sleep(60000)

    using(Git.open(new File(config.directory, repositoryName))){ git =>
      if(git.getRepository.resolve(Constants.HEAD) != null){
        git.push().setRemote(targetUrl).setPushAll().call()
      }
    }
  }

}