package nl.yogh.aerius.server.worker;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.yogh.aerius.builder.domain.CompositionDeploymentAction;
import nl.yogh.aerius.builder.domain.CompositionInfo;
import nl.yogh.aerius.builder.domain.ServiceInfo;
import nl.yogh.aerius.server.util.ApplicationConfiguration;
import nl.yogh.aerius.server.util.HashUtil;
import nl.yogh.aerius.server.worker.jobs.CatchAllRunnable;
import nl.yogh.aerius.server.worker.jobs.CompositionCompilationJob;
import nl.yogh.aerius.server.worker.jobs.CompositionDeploymentJob;
import nl.yogh.aerius.server.worker.jobs.CompositionSuspensionJob;

public class PullRequestDeploymentWorker {
  private static final Logger LOG = LoggerFactory.getLogger(PullRequestDeploymentWorker.class);

  private static final int CACHE_MINUTES = 15;
  private static final long CACHE_MILLISECONDS = CACHE_MINUTES * 60 * 1000;

  private final ExecutorService projectCompilationExecutor;
  private final ExecutorService projectDeploymentExecutor;
  private final ExecutorService projectSuspensionExecutor;

  private final ScheduledExecutorService clearCacheExecutor;

  private final Map<Long, List<CompositionInfo>> projectUpdates;
  private final Map<Long, List<ServiceInfo>> serviceUpdates;

  private final ConcurrentMap<String, CompositionInfo> projects;
  private final ConcurrentMap<String, ServiceInfo> services;

  private final ApplicationConfiguration cfg;

  public PullRequestDeploymentWorker(final ApplicationConfiguration cfg, final ConcurrentMap<String, CompositionInfo> projects,
      final ConcurrentMap<String, ServiceInfo> services, final Map<Long, List<CompositionInfo>> projectUpdates,
      final Map<Long, List<ServiceInfo>> serviceUpdates) {
    this.cfg = cfg;
    this.projects = projects;
    this.services = services;
    this.projectUpdates = projectUpdates;
    this.serviceUpdates = serviceUpdates;
    clearCacheExecutor = Executors.newSingleThreadScheduledExecutor();
    clearCacheExecutor.scheduleWithFixedDelay(() -> {
      final long clearBefore = new Date().getTime() - CACHE_MILLISECONDS;
      synchronized (projectUpdates) {
        projectUpdates.keySet().removeIf(o -> o < clearBefore);
      }

      synchronized (serviceUpdates) {
        serviceUpdates.keySet().removeIf(o -> o < clearBefore);
      }

      LOG.info("UpdateCache cleared up to {}", new Date(clearBefore).toString());
    }, 0, CACHE_MINUTES, TimeUnit.MINUTES);

    projectCompilationExecutor = Executors.newSingleThreadExecutor();
    projectDeploymentExecutor = Executors.newFixedThreadPool(2);
    projectSuspensionExecutor = Executors.newFixedThreadPool(2);
  }

  public void shutdown() {
    clearCacheExecutor.shutdownNow();
  }

  public void doAction(final String idx, final CompositionDeploymentAction action, final CompositionInfo info) {
    LOG.info("Doing action {} on {} -- current status: {}", action, HashUtil.shorten(info.hash()), info.status());

    switch (action) {
    case BUILD:
      projectCompilationExecutor
          .submit(CatchAllRunnable.wrap(new CompositionCompilationJob(cfg, info, idx, projectUpdates, serviceUpdates, projects, services)));
      break;
    case SUSPEND:
      projectSuspensionExecutor
          .submit(CatchAllRunnable.wrap(new CompositionSuspensionJob(cfg, info, idx, projectUpdates, serviceUpdates, projects, services)));
      break;
    case DEPLOY:
      projectDeploymentExecutor
          .submit(CatchAllRunnable.wrap(new CompositionDeploymentJob(cfg, info, idx, projectUpdates, serviceUpdates, projects, services)));
      break;
    case DESTROY:
    default:
      LOG.info("Not implemented yet or unknown: {}", action);
    }
  }

  public ArrayList<CompositionInfo> getProjects() {
    return new ArrayList<>(projects.values());
  }

  public ArrayList<ServiceInfo> getServices() {
    return new ArrayList<>(services.values());
  }

  public void purge() {
    projects.clear();
    services.clear();
  }
}
