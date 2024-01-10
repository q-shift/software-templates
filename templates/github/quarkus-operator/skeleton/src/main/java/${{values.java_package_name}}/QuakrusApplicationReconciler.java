package ${{ values.java_package_name }};

import static io.javaoperatorsdk.operator.api.reconciler.Constants.WATCH_CURRENT_NAMESPACE;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata;
import io.quarkiverse.operatorsdk.annotations.CSVMetadata.Icon;
import jakarta.inject.Inject;

@CSVMetadata(permissionRules = @CSVMetadata.PermissionRule(apiGroups = QuarkusApplication.GROUP, resources = QuarkusApplication.PLURAL), requiredCRDs = @CSVMetadata.RequiredCRD(kind = QuarkusApplication.KIND, name = QuarkusApplication.NAME, version = QuarkusApplication.VERSION), icon = @Icon(fileName = "icon.png", mediatype = "image/png"))
@ControllerConfiguration(namespaces = WATCH_CURRENT_NAMESPACE)
@SuppressWarnings("unused")
public class QuakrusApplicationReconciler implements Reconciler<QuarkusApplication> {

  @Inject
  KubernetesClient client;

  @Override
  public UpdateControl<QuarkusApplication> reconcile(QuarkusApplication quarkusApplication, Context<QuarkusApplication> context)
      throws Exception {
    var spec = quarkusApplication.getSpec();
    var status = quarkusApplication.getStatus();

    if (status != null && QuarkusApplicationStatus.State.CREATED == status.getState()) {
      return UpdateControl.noUpdate();
    }

    try {
      if (!status.isError()) {
        OpenShiftDeployer deployer = new OpenShiftDeployer(client, spec.getCoordinates(), spec.getRepositoryUrl());
        deployer.build();
        deployer.deploy();
      }
    } catch (Exception e) {
      status = new QuarkusApplicationStatus();
      status.setMessage("Error querying API: " + e.getMessage());
      status.setState(QuarkusApplicationStatus.State.ERROR);
      status.setError(true);
    }

    quarkusApplication.setStatus(status);
    return UpdateControl.updateStatus(quarkusApplication);
  }

}
