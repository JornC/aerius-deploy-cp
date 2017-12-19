package nl.yogh.aerius.server.startup;

import java.util.Properties;

import nl.yogh.aerius.builder.domain.ProjectInfo;
import nl.yogh.aerius.builder.domain.ServiceInfo;
import nl.yogh.aerius.server.worker.ProjectUpdateRepositoryFactory;
import nl.yogh.aerius.server.worker.PullRequestDeploymentFactory;
import nl.yogh.aerius.server.worker.PullRequestMaintenanceFactory;
import nl.yogh.aerius.server.worker.ServiceUpdateRepositoryFactory;

/**
 * Should just set up injection but... oh well.
 */
public class ApplicationFactory {
  public static void init(final Properties properties) {
    final TimestampedMultiMap<ServiceInfo> serviceUpdates = ServiceUpdateRepositoryFactory.getInstance();
    final TimestampedMultiMap<ProjectInfo> projectUpdates = ProjectUpdateRepositoryFactory.getInstance();

    PullRequestMaintenanceFactory.init(System.getProperties(), projectUpdates);
    PullRequestDeploymentFactory.init(System.getProperties(), projectUpdates, serviceUpdates);
  }

  public static void shutdown() {
    PullRequestMaintenanceFactory.shutdown();
    PullRequestDeploymentFactory.shutdown();
  }
}