package ${{ values.java_package_name }};

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.jboss.logging.Logger;

import io.fabric8.kubernetes.api.builder.Visitor;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.KubernetesListBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.openshift.api.model.Build;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamBuilder;
import io.fabric8.openshift.api.model.ImageStreamTag;
import io.fabric8.openshift.api.model.SourceBuildStrategyFluent;
import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.container.image.openshift.deployment.BuildStatus;
import io.quarkus.kubernetes.client.deployment.KubernetesClientErrorHandler;

public class OpenShiftDeployer {

  private static final Logger LOG = Logger.getLogger(OpenShiftDeployer.class);

  private static final int LOG_TAIL_SIZE = 10;

  private static final String DEFAULT_BUILDER_IMAGE_REPO = "registry.access.redhat.com/ubi8/openjdk-17";
  private static final String DEFAULT_BUILDER_IMAGE_TAG = "1.18";
  private static final String DEFAULT_BUILDER_IMAGE_NAME = "openjdk-17";

  private static final String IMAGESTREAMTAG = "ImageStreamTag";
  private static final String LATEST = "latest";

  private static final String DEFAULT_REPOSITORY_URL = "https://repo1.maven.org/maven2";
  private static final String ILLEGAL_COORDINATES_FORMAT = "Invalid coordinates: %s. Please use <groupId>:<artifactId>:<version> format!";

  private final KubernetesClient kubernetesClient;
  private final String name;

  private final String coordinates;
  private final String repositoryUrl;

  private final String groupId;
  private final String artifactId;
  private final String version;
  private final Optional<String> classifier;
  private final String type;

  private final String jarFileName;

  public OpenShiftDeployer(String coordinates, String repositoryUrl) {
    this(new KubernetesClientBuilder().build(), artifactId(coordinates), groupId(coordinates), artifactId(coordinates),
        classifier(coordinates), type(coordinates),
        version(coordinates), repositoryUrl);
  }

  public OpenShiftDeployer(String name, String coordinates, String repositoryUrl) {
    this(new KubernetesClientBuilder().build(), name, groupId(coordinates), artifactId(coordinates), classifier(coordinates),
        type(coordinates), version(coordinates),
        repositoryUrl);
  }

  public OpenShiftDeployer(KubernetesClient kubernetesClient, String name, String coordinates, String repositoryUrl) {
    this(kubernetesClient, name, groupId(coordinates), artifactId(coordinates), classifier(coordinates), type(coordinates),
        version(coordinates), repositoryUrl);
  }

  public OpenShiftDeployer(KubernetesClient kubernetesClient, String coordinates, String repositoryUrl) {
    this(kubernetesClient, artifactId(coordinates), groupId(coordinates), artifactId(coordinates), classifier(coordinates),
        type(coordinates), version(coordinates),
        repositoryUrl);
  }

  public OpenShiftDeployer(KubernetesClient kubernetesClient, String name, String groupId, String artifactId,
      Optional<String> classifier, String type, String version,
      String repositoryUrl) {
    this.kubernetesClient = kubernetesClient;
    this.name = name;
    this.repositoryUrl = repositoryUrl;
    this.groupId = groupId;
    this.artifactId = artifactId;
    this.classifier = classifier;
    this.type = type;
    this.version = version;
    this.coordinates = groupId + ":" + artifactId + ":" + version;
    this.jarFileName = artifactId + "-" + version + classifier.map(c -> "-" + c).orElse("") + "." + type;
  }

  public void build() {
    System.out.println("Building ...");
    BuildConfig buildConfig = createBuildConfig(name, LATEST, DEFAULT_BUILDER_IMAGE_NAME, DEFAULT_BUILDER_IMAGE_TAG);
    ImageStream outputImageStream = createImageStream(name);
    ImageStream builderImageStream = createImageStrem(DEFAULT_BUILDER_IMAGE_NAME, DEFAULT_BUILDER_IMAGE_REPO);
    if (kubernetesClient.resource(buildConfig).get() != null) {
      kubernetesClient.resource(buildConfig).delete();
    }
    if (kubernetesClient.resource(outputImageStream).get() != null) {
      kubernetesClient.resource(outputImageStream).delete();
    }
    //Let's not delete the builder image.
    Path downloadedJar = download();
    kubernetesClient.resourceList(buildConfig, outputImageStream, builderImageStream).serverSideApply();

    waitForImageStreamTags(List.of(builderImageStream), 5L, TimeUnit.MINUTES);
    Build build = startOpenshiftBuild(buildConfig, downloadedJar.toFile());
    waitForOpenshiftBuild(build);
  }

  public void deploy() {
    System.out.println("Deploying ...");
    Map<String, String> labels = Map.of("app.kubernetes.io/name", name, "app.kubernetes.io/version", version);
    Deployment deployment = new DeploymentBuilder()
        .withNewMetadata()
        .withName(name)
        .withLabels(labels)
        .endMetadata()
        .withNewSpec()
        .withReplicas(1)
        .withNewSelector()
        .withMatchLabels(labels)
        .endSelector()
        .withNewTemplate()
        .withNewMetadata()
        .withLabels(labels)
        .endMetadata()
        .withNewSpec()
        .addNewContainer()
        .withName(name)
        .withImage(name + ":" + version)
        .withImagePullPolicy("IfNotPresent")
        .withPorts(new ContainerPortBuilder().withName("http").withContainerPort(8080).build())
        .addNewEnv()
        .withName("JAVA_APP_JAR")
        .withValue("/deployments/" + jarFileName)
        .endEnv()
        .endContainer()
        .endSpec()
        .endTemplate()
        .endSpec()
        .build();
    kubernetesClient.resource(deployment).serverSideApply();
  }

  protected Path download() {
    System.out.println("Downloading artifact: " + coordinates);
    try {
      Path destination = Files.createTempDirectory("maven-artifact").resolve(artifactId + "-" + version + ".jar");
      download(destination);
      System.out.println("Downloaded artifact at: " + destination.toAbsolutePath());
      return destination;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  protected void download(Path destination) {
    String artifactPath = groupId.replace(".", "/") + "/" + artifactId + "/" + version + "/" + artifactId + "-" + version
        + ".jar";
    String downloadUrl = repositoryUrl.endsWith("/") ? repositoryUrl + artifactPath : repositoryUrl + "/" + artifactPath;

    try {
      URL url = new URL(downloadUrl);
      HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();

      try (InputStream inputStream = new BufferedInputStream(httpURLConnection.getInputStream());
          FileOutputStream outputStream = new FileOutputStream(destination.toFile())) {

        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
          outputStream.write(buffer, 0, bytesRead);
        }
      } finally {
        httpURLConnection.disconnect();
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  /**
   * Performs the binary build of the specified {@link BuildConfig} with the given
   * binary input.
   *
   * @param buildConfig The build config
   * @param binaryFile The binary file
   * @param openshiftConfig The openshift configuration
   * @param kubernetesClientBuilder The kubernetes client builder
   */
  private Build startOpenshiftBuild(BuildConfig buildConfig, File binaryFile) {
    System.out.println("Starting OpenShift Build ...");
    OpenShiftClient client = toOpenshiftClient(kubernetesClient);
    try {
      return client.buildConfigs().withName(buildConfig.getMetadata().getName())
          .instantiateBinary()
          .withTimeoutInMillis(300000)
          .fromFile(binaryFile);

    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void waitForOpenshiftBuild(Build build) {
    while (isNew(build) || isPending(build) || isRunning(build)) {
      final String buildName = build.getMetadata().getName();
      OpenShiftClient client = toOpenshiftClient(kubernetesClient);
      Build updated = client.builds().withName(buildName).get();
      if (updated == null) {
        throw new IllegalStateException("Build:" + build.getMetadata().getName() + " is no longer present!");
      } else if (updated.getStatus() == null) {
        throw new IllegalStateException("Build:" + build.getMetadata().getName() + " has no status!");
      } else if (isNew(updated) || isPending(updated) || isRunning(updated)) {
        build = updated;
        try (LogWatch w = client.builds().withName(buildName).withPrettyOutput().watchLog();
            Reader reader = new InputStreamReader(w.getOutput())) {
          display(reader, Logger.Level.INFO);
        } catch (IOException | KubernetesClientException ex) {
          // This may happen if the LogWatch is closed while we are still reading.
          // We shouldn't let the build fail, so let's log a warning and display last few lines of the log
          LOG.warn("Log stream closed, redisplaying last " + LOG_TAIL_SIZE + " entries:");
          try {
            display(client.builds().withName(buildName).tailingLines(LOG_TAIL_SIZE).getLogReader(),
                Logger.Level.WARN);
          } catch (IOException | KubernetesClientException ignored) {
            // Let's ignore this.
          }
        }
      } else if (isComplete(updated)) {
        return;
      } else if (isCancelled(updated)) {
        throw new IllegalStateException("Build:" + buildName + " cancelled!");
      } else if (isFailed(updated)) {
        throw new IllegalStateException(
            "Build:" + buildName + " failed! " + updated.getStatus().getMessage());
      } else if (isError(updated)) {
        throw new IllegalStateException(
            "Build:" + buildName + " encountered error! " + updated.getStatus().getMessage());
      }
    }
  }

  /**
   * Wait for the references ImageStreamTags to become available.
   *
   * @param items A list of items, possibly referencing image stream tags.
   * @param amount The max amount of time to wait.
   * @param timeUnit The time unit of the time to wait.
   * @return True if the items became available false otherwise.
   */
  public boolean waitForImageStreamTags(Collection<HasMetadata> items, long amount, TimeUnit timeUnit) {
    System.out.println("Waiting tags for images: "
        + items.stream().map(HasMetadata::getMetadata).map(ObjectMeta::getName).collect(Collectors.joining(", "))
        + " to become available...");
    OpenShiftClient client = toOpenshiftClient(kubernetesClient);
    if (items == null || items.isEmpty()) {
      return true;
    }
    final List<String> tags = new ArrayList<>();
    new KubernetesListBuilder()
        .withItems(new ArrayList<>(items))
        .accept(new Visitor<SourceBuildStrategyFluent>() {
          @Override
          public void visit(SourceBuildStrategyFluent strategy) {
            ObjectReference from = strategy.buildFrom();
            if (from.getKind().equals("ImageStreamTag")) {
              tags.add(from.getName());
            }
          }
        }).build();

    boolean tagsMissing = true;
    long started = System.currentTimeMillis();
    long elapsed = 0;

    while (tagsMissing && elapsed < timeUnit.toMillis(amount) && !Thread.interrupted()) {
      tagsMissing = false;
      for (String tag : tags) {
        ImageStreamTag t = client.imageStreamTags().withName(tag).get();
        if (t == null) {
          tagsMissing = true;
        }
      }

      if (tagsMissing) {
        try {
          Thread.sleep(1000);
          elapsed = System.currentTimeMillis() - started;
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }
    return !tagsMissing;
  }

  private static OpenShiftClient toOpenshiftClient(KubernetesClient client) {
    try {
      return client.adapt(OpenShiftClient.class);
    } catch (KubernetesClientException e) {
      KubernetesClientErrorHandler.handle(e);
      return null; // will never happen
    }
  }

  private static final ImageStream createImageStream(String name) {
    return new ImageStreamBuilder()
        .withNewMetadata()
        .withName(name)
        .endMetadata()
        .withNewSpec()
        .withNewLookupPolicy().withLocal(true).endLookupPolicy()
        .endSpec()
        .build();
  }

  private static final ImageStream createImageStrem(String name, String dockerImageRepository) {
    return new ImageStreamBuilder()
        .withNewMetadata()
        .withName(name)
        .endMetadata()
        .withNewSpec()
        .withDockerImageRepository(dockerImageRepository)
        .withNewLookupPolicy().withLocal(true).endLookupPolicy()
        .endSpec()
        .build();
  }

  private static final BuildConfig createBuildConfig(String name, String version, String builderName, String builderTag) {
    return new BuildConfigBuilder()
        .withNewMetadata()
        .withName(name)
        .endMetadata()
        .withNewSpec()
        .withNewOutput()
        .withNewTo()
        .withKind(IMAGESTREAMTAG)
        .withName(name + ":" + version)
        .endTo()
        .endOutput()
        .withNewSource()
        .withNewBinary()
        .endBinary()
        .endSource()
        .withNewStrategy()
        .withNewSourceStrategy()
        .withEnv()
        .withNewFrom()
        .withKind(IMAGESTREAMTAG)
        .withName(builderName + ":" + builderTag)
        .endFrom()
        .endSourceStrategy()
        .endStrategy()
        .endSpec()
        .build();
  }

  private static void display(Reader logReader, Logger.Level level) throws IOException {
    BufferedReader reader = new BufferedReader(logReader);
    for (String line = reader.readLine(); line != null; line = reader.readLine()) {
      LOG.log(level, line);
    }
  }

  private static boolean isNew(Build build) {
    return build != null && build.getStatus() != null
        && BuildStatus.New.name().equalsIgnoreCase(build.getStatus().getPhase());
  }

  private static boolean isPending(Build build) {
    return build != null && build.getStatus() != null
        && BuildStatus.Pending.name().equalsIgnoreCase(build.getStatus().getPhase());
  }

  private static boolean isRunning(Build build) {
    return build != null && build.getStatus() != null
        && BuildStatus.Running.name().equalsIgnoreCase(build.getStatus().getPhase());
  }

  private static boolean isComplete(Build build) {
    return build != null && build.getStatus() != null
        && BuildStatus.Complete.name().equalsIgnoreCase(build.getStatus().getPhase());
  }

  private static boolean isFailed(Build build) {
    return build != null && build.getStatus() != null
        && BuildStatus.Failed.name().equalsIgnoreCase(build.getStatus().getPhase());
  }

  private static boolean isError(Build build) {
    return build != null && build.getStatus() != null
        && BuildStatus.Error.name().equalsIgnoreCase(build.getStatus().getPhase());
  }

  private static boolean isCancelled(Build build) {
    return build != null && build.getStatus() != null
        && BuildStatus.Cancelled.name().equalsIgnoreCase(build.getStatus().getPhase());
  }

  private static String groupId(String coordinates) {
    try {
      return coordinates.split(":")[0];
    } catch (IndexOutOfBoundsException e) {
      throw new IllegalArgumentException(String.format(ILLEGAL_COORDINATES_FORMAT, coordinates));
    }
  }

  private static String artifactId(String coordinates) {
    try {
      return coordinates.split(":")[1];
    } catch (IndexOutOfBoundsException e) {
      throw new IllegalArgumentException(String.format(ILLEGAL_COORDINATES_FORMAT, coordinates));
    }
  }

  private static String version(String coordinates) {
    try {
      return coordinates.split(":")[2];
    } catch (IndexOutOfBoundsException e) {
      throw new IllegalArgumentException(String.format(ILLEGAL_COORDINATES_FORMAT, coordinates));
    }
  }

  private static Optional<String> classifier(String coordinates) {
    //GAV
    //GACTV
    String[] parts = coordinates.split(":");
    if (parts.length >= 5) {
      return Optional.of(parts[4]);
    }
    return Optional.empty();
  }

  private static String type(String coordinates) {
    //GAV
    //GACTV
    String[] parts = coordinates.split(":");
    if (parts.length > 3) {
      return parts[3];
    }
    return "jar";
  }
}
