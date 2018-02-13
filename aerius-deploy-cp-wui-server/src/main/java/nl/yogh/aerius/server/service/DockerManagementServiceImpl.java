package nl.yogh.aerius.server.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.yogh.aerius.builder.domain.DockerContainer;
import nl.yogh.aerius.builder.domain.DockerImage;
import nl.yogh.aerius.builder.domain.ProjectInfo;
import nl.yogh.aerius.builder.domain.ProjectStatus;
import nl.yogh.aerius.builder.domain.ServiceInfo;
import nl.yogh.aerius.builder.domain.ServiceStatus;
import nl.yogh.aerius.builder.exception.ApplicationException;
import nl.yogh.aerius.builder.exception.ApplicationException.Reason;
import nl.yogh.aerius.builder.service.DockerManagementService;
import nl.yogh.aerius.server.startup.TimestampedMultiMap;
import nl.yogh.aerius.server.util.CmdUtil;
import nl.yogh.aerius.server.util.CmdUtil.ProcessExitException;
import nl.yogh.aerius.server.worker.ProjectUpdateRepositoryFactory;
import nl.yogh.aerius.server.worker.ServiceUpdateRepositoryFactory;

public class DockerManagementServiceImpl extends AbstractServiceImpl implements DockerManagementService {
  private static final Logger LOG = LoggerFactory.getLogger(DockerManagementServiceImpl.class);

  private static final ExecutorService executor = Executors.newSingleThreadExecutor();

  @Override
  public void stopAllContainers() throws ApplicationException {
    executor.submit(() -> {
      LOG.info("[COMMAND] Stopping all containers.");

      try {
        cmd("docker stop $(docker ps -aq --filter status=running --filter label=nl.aerius.docker.service=true)");
        final TimestampedMultiMap<ProjectInfo> projectUpdates = ProjectUpdateRepositoryFactory.getInstance();
        getDeploymentInstance().getProjects().stream().forEach(v -> projectUpdates.timestamp(v.status(ProjectStatus.SUSPENDED)));
      } catch (IOException | InterruptedException | ProcessExitException | ApplicationException e) {
        LOG.error("Error during stopAll()", e);
      } finally {
        LOG.info("Done stopping all containers.");
      }
    });
  }

  @Override
  public void removeAllContainers() throws ApplicationException {
    executor.submit(() -> {
      LOG.info("[COMMAND] Removing all containers.");

      try {
        cmd("docker rm $(docker ps -aq --filter status=exited --filter label=nl.aerius.docker.service=true)");
        final TimestampedMultiMap<ProjectInfo> projectUpdates = ProjectUpdateRepositoryFactory.getInstance();
        getDeploymentInstance().getProjects().stream().forEach(v -> projectUpdates.timestamp(v.status(ProjectStatus.UNBUILT)));
      } catch (IOException | InterruptedException | ProcessExitException | ApplicationException e) {
        LOG.error("Error during stopAll()", e);
      } finally {
        LOG.info("[COMMAND] Done removing all containers.");
      }
    });
  }

  @Override
  public void removeAllImages() throws ApplicationException {
    executor.submit(() -> {
      LOG.info("[COMMAND] Pruning images.");

      try {
        cmd("docker rmi $(docker images -q --filter label=nl.aerius.docker.service=true)");
        final TimestampedMultiMap<ServiceInfo> serviceUpdates = ServiceUpdateRepositoryFactory.getInstance();
        getDeploymentInstance().getServices().stream().forEach(v -> serviceUpdates.timestamp(v.status(ServiceStatus.UNBUILT)));
      } catch (IOException | InterruptedException | ProcessExitException | ApplicationException e) {
        LOG.error("Error during stopAll()", e);
      } finally {
        LOG.info("[COMMAND] Done pruning images.");
      }
    });
  }

  @Override
  public ArrayList<DockerContainer> retrieveContainers() throws ApplicationException {
    final ArrayList<DockerContainer> containers = new ArrayList<>();

    try {
      cmd("docker ps -a --format {{.ID}},{{.Image}},{{.Names}} --filter label=nl.aerius.docker.service=true").stream()
          .map(v -> v.split(","))
          .map(v -> DockerContainer.create().hash(v[0]).image(v[1]).name(v[2]))
          .forEach(v -> containers.add(v));
    } catch (IOException | InterruptedException | ProcessExitException e) {
      LOG.error("Error during stopAll()", e);
    }

    return containers;
  }

  @Override
  public ArrayList<DockerImage> retrieveImages() throws ApplicationException {
    final ArrayList<DockerImage> images = new ArrayList<>();

    try {
      cmd("docker images --format {{.ID}},{{.Repository}},{{.Tag}} --filter label=nl.aerius.docker.service=true").stream()
          .map(v -> v.split(","))
          .map(v -> DockerImage.create().hash(v[0]).name(v[1]).tag(v[2]))
          .forEach(v -> images.add(v));
    } catch (IOException | InterruptedException | ProcessExitException e) {
      LOG.error("Error during stopAll()", e);
    }

    return images;
  }

  @Override
  public boolean stopContainer(final DockerContainer container) throws ApplicationException {
    final CountDownLatch latch = new CountDownLatch(1);
    final CompletableFuture<Exception> fut = new CompletableFuture<>();

    executor.submit(() -> {
      LOG.info("[COMMAND] Stopping container {}", container.hash());

      try {
        cmd("docker stop %s", container.hash());
        latch.countDown();
      } catch (IOException | InterruptedException | ProcessExitException e) {
        LOG.error("Error during stopContainer {}", container.hash(), e);
        fut.complete(e);
      } finally {
        LOG.info("[COMMAND] Done stopping container {}", container.hash());
      }
    });

    return await(latch, fut);
  }

  @Override
  public boolean removeContainer(final DockerContainer container) throws ApplicationException {
    final CountDownLatch latch = new CountDownLatch(1);
    final CompletableFuture<Exception> fut = new CompletableFuture<>();

    executor.submit(() -> {
      LOG.info("[COMMAND] Removing container {}", container.hash());

      try {
        cmd("docker rm %s", container.hash());
        latch.countDown();
      } catch (IOException | InterruptedException | ProcessExitException e) {
        LOG.error("Error during removeContainer {}", container.hash(), e);
        fut.complete(e);
      } finally {
        LOG.info("[COMMAND] Done removing container {}", container.hash());
      }
    });

    return await(latch, fut);
  }

  private boolean await(final CountDownLatch latch, final Future<Exception> fut) throws ApplicationException {
    try {
      final boolean complete = latch.await(5, TimeUnit.SECONDS);
      if (complete) {
        if (fut.isDone()) {
          try {
            throw new ApplicationException(Reason.INTERNAL_ERROR, fut.get().getMessage());
          } catch (final ExecutionException e) {
            LOG.error("Error during await {}", e);
          }
        }
      }

      return complete;
    } catch (final InterruptedException e) {}

    return false;
  }

  @Override
  public boolean removeImage(final DockerImage image) throws ApplicationException {
    final CountDownLatch latch = new CountDownLatch(1);
    final CompletableFuture<Exception> fut = new CompletableFuture<>();

    executor.submit(() -> {
      LOG.info("[COMMAND] Removing image {}", image.hash());

      try {
        cmd("docker rmi %s", image.hash());
        latch.countDown();
      } catch (IOException | InterruptedException | ProcessExitException e) {
        LOG.error("Error during removeImage {}", image.hash(), e);
        fut.complete(e);
      } finally {
        LOG.info("[COMMAND] Done removing image {}", image.hash());
      }
    });

    return await(latch, fut);
  }

  @Override
  public HashMap<String, String> retrieveStats() throws ApplicationException {
    final LinkedHashMap<String, String> stats = new LinkedHashMap<>();

    // Should probably be shot for this [general approach], but hey when it works..
    try {
      stats.put("Disk Usage", cmd("df -h --output=target,pcent | grep ^/[[:space:]] | rev | cut -d ' ' -f 1 | rev").get(0));
      stats.put("Pull requests", String.valueOf(getMaintenanceInstance().getPullRequests().size()));
      stats.put("Projects", String.valueOf(getDeploymentInstance().getProjects().size()));
      stats.put("Services", String.valueOf(getDeploymentInstance().getServices().size()));
    } catch (IOException | InterruptedException | ProcessExitException | ApplicationException e) {
      LOG.error("Internal error.", e);
      throw new ApplicationException(Reason.INTERNAL_ERROR, e.getMessage());
    }

    return stats;
  }

  private ArrayList<String> cmd(final String format, final Object... args) throws IOException, InterruptedException, ProcessExitException {
    return cmd(String.format(format, args));
  }

  private ArrayList<String> cmd(final String cmd) throws IOException, InterruptedException, ProcessExitException {
    // return CmdUtil.cmdDebug("/", cmd);
    return CmdUtil.cmd("/", cmd);
  }

  @Override
  public void purgeTracker() throws ApplicationException {
    getDeploymentInstance().purge();
    getMaintenanceInstance().purge();
  }
}
