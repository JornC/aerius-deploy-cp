package nl.yogh.aerius.server.worker.jobs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import nl.yogh.aerius.builder.domain.ProjectInfo;
import nl.yogh.aerius.builder.domain.ProjectStatus;
import nl.yogh.aerius.builder.domain.ProjectType;
import nl.yogh.aerius.builder.domain.ServiceInfo;
import nl.yogh.aerius.builder.domain.ServiceStatus;
import nl.yogh.aerius.builder.domain.ServiceType;
import nl.yogh.aerius.server.util.ApplicationConfiguration;
import nl.yogh.aerius.server.util.CmdUtil;
import nl.yogh.aerius.server.util.CmdUtil.ProcessExitException;
import nl.yogh.aerius.server.util.Files;
import nl.yogh.aerius.server.util.HashUtil;

public class GenericCompilationJob extends ProjectJob {
  private static final Logger LOG = LoggerFactory.getLogger(GenericCompilationJob.class);

  private final ExecutorService executor = Executors.newFixedThreadPool(1);

  private final Map<String, String> globalReplacements;

  private final String prId;

  public GenericCompilationJob(final ApplicationConfiguration cfg, final String prId, final ProjectInfo info,
      final Map<Long, List<ProjectInfo>> projectUpdates, final Map<Long, List<ServiceInfo>> serviceUpdates,
      final ConcurrentMap<String, ProjectInfo> projects, final ConcurrentMap<String, ServiceInfo> services) {
    super(cfg, info, projectUpdates, serviceUpdates, projects, services);
    this.prId = prId;
    putProject(info.busy(true));

    globalReplacements = cfg.getControlPanelProperties().collect(Collectors.toMap(formatKey(), v -> (String) v.getValue()));
    globalReplacements.put("{{cp.pr.id}}", prId);
    globalReplacements.put("{{cp.pr.hash}}", info.hash());

    LOG.debug("Loaded {} global replacements for compilation job.", globalReplacements.size());
  }

  private Function<Entry<Object, Object>, String> formatKey() {
    return v -> String.format("{{%s}}", v.getKey());
  }

  @Override
  public void run() {
    LOG.info("Compiling project: {} {}", info.type(), HashUtil.shorten(info.hash()));

    final List<ServiceType> exclusions = services.entrySet().stream()
        .filter(v -> info.services().stream().map(vv -> vv.hash()).anyMatch(r -> r.equals(v.getKey()))).map(v -> v.getValue())
        .filter(v -> v.status() != ServiceStatus.UNBUILT).map(v -> v.type()).collect(Collectors.toList());

    LOG.info("Service compilation exclusions: {}", exclusions);

    final List<ServiceInfo> targets = info.services().stream().filter(v -> !exclusions.contains(v.type())).collect(Collectors.toList());

    final CountDownLatch latch = new CountDownLatch(targets.size());

    for (final ServiceInfo service : targets) {
      executor.submit(() -> {
        LOG.info("Compiling service: {} {}", service.type(), HashUtil.shorten(service.hash()));
        try {
          final ServiceInfo resultInfo = compileService(service);
          putService(resultInfo);
        } catch (final Exception e) {
          LOG.error("Exception while compiling service..");
        }

        latch.countDown();
      });
    }

    try {
      latch.await();
    } catch (final InterruptedException e) {
      LOG.error("Exception while waiting for latch.", e);
      return;
    }

    putProject(deployProject(prId, info));
  }

  private ProjectInfo deployProject(final String prId, final ProjectInfo info) {
    final File tmpDir = Files.createTempDir();

    final Map<String, String> localReplacements = new HashMap<>();
    info.services().forEach(v -> localReplacements.put(String.format("{{service.%s.hash}}", v.type()), v.hash()));
    localReplacements.put("{{project.hash}}", info.hash());

    // First, copy the staging dir to a temporary dir
    moveStagingDirectory(tmpDir, info.type());

    // Next, replace all replacement markers
    replaceOccurrences(tmpDir, globalReplacements);
    replaceOccurrences(tmpDir, localReplacements);

    // Finally, run the deploy script
    final boolean success = deploy(tmpDir);

    LOG.info("Project deployment result: {} for {} ({})", success ? "SUCCESS" : "FAIL", info.type(), HashUtil.shorten(info.hash()));

    if (success) {
      final String url = String.format(cfg.getDeploymentHost(info.type()), prId);
      LOG.info("Deployed project to: {}", url);
      info.url(url);
    }

    return info.status(success ? ProjectStatus.DEPLOYED : ProjectStatus.UNBUILT).busy(false);
  }

  private ServiceInfo compileService(final ServiceInfo service) {
    final File tmpDir = Files.createTempDir();

    final Map<String, String> localReplacements = new HashMap<>();
    localReplacements.put("{{service.local.hash}}", service.hash());

    // First, copy the staging dir to a temporary dir
    moveStagingDirectory(tmpDir, service.type());

    // Next, replace all replacement markers
    replaceOccurrences(tmpDir, globalReplacements);
    replaceOccurrences(tmpDir, localReplacements);

    // Finally, run the install script
    final boolean success = install(tmpDir);

    LOG.info("Service compilation result: {} for {} ({})", success ? "SUCCESS" : "FAIL", service.type(), HashUtil.shorten(service.hash()));

    return ServiceInfo.create(service).status(success ? ServiceStatus.BUILT : ServiceStatus.UNBUILT);
  }

  private void replaceOccurrences(final File dir, final Map<String, String> replacements) {
    for (final Entry<String, String> entry : replacements.entrySet()) {
      try {
        cmd(dir, "sed -i -- 's/%s/%s/g' *", entry.getKey(), escape(entry.getValue()));
      } catch (IOException | InterruptedException | ProcessExitException e) {
        LOG.trace("Error during r.");
        // eat
      }
    }
  }

  private String escape(final String value) {
    return value.replace("/", "\\/");
  }

  private void moveStagingDirectory(final File dir, final ServiceType type) {
    moveStagingDirectory(dir, String.format("%s/services/%s/", cfg.getStagingDir(), type.name()));
  }

  private void moveStagingDirectory(final File dir, final ProjectType type) {
    moveStagingDirectory(dir, String.format("%s/projects/%s/", cfg.getStagingDir(), type.name()));
  }

  private void moveStagingDirectory(final File dir, final String source) {
    LOG.debug("Copying: {} to {}", cfg.getStagingDir(), dir.getAbsolutePath());
    try {
      cmd(source, "cp -a * %s", dir.getAbsolutePath());
    } catch (IOException | InterruptedException | ProcessExitException e) {
      LOG.trace("Error during copy.");
      // eat
    }
  }

  private boolean install(final File dir) {
    try {
      cmd(dir, "./install.sh");
      return true;
    } catch (final ProcessExitException e) {
      LOG.trace("Error during install: " + e.getOutput().get(0));
    } catch (IOException | InterruptedException e) {
      LOG.trace("Unknown error during install.");
      // eat
    }

    return false;
  }

  private boolean deploy(final File dir) {
    try {
      cmd(dir, "./deploy.sh");
      return true;
    } catch (final ProcessExitException e) {
      LOG.info("Error during deployment: " + e.getOutput().get(0));
    } catch (IOException | InterruptedException e) {
      LOG.trace("Unknown error during deployment.");
      // eat
    }

    return false;
  }

  private ArrayList<String> cmd(final File dir, final String format, final String... args)
      throws IOException, InterruptedException, ProcessExitException {
    return cmd(dir, String.format(format, (Object[]) args));
  }

  private ArrayList<String> cmd(final File dir, final String cmd) throws IOException, InterruptedException, ProcessExitException {
    // return CmdUtil.cmdDebug(dir, cmd);
    return CmdUtil.cmd(dir, cmd);
  }

  private ArrayList<String> cmd(final String dir, final String format, final String... args)
      throws IOException, InterruptedException, ProcessExitException {
    return cmd(dir, String.format(format, (Object[]) args));
  }

  private ArrayList<String> cmd(final String dir, final String cmd) throws IOException, InterruptedException, ProcessExitException {
    // return CmdUtil.cmdDebug(dir, cmd);
    return CmdUtil.cmd(dir, cmd);
  }
}
