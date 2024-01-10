package ${{ values.java_package_name }};

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

@Group("acme.org")
@Version("v1alpha1")
@ShortNames("qapp")
public class QuarkusApplication extends CustomResource<QuarkusApplicationSpec, QuarkusApplicationStatus> implements Namespaced {

  public static final String GROUP = "acme.org";
  public static final String VERSION = "v1alpha1";
  public static final String KIND = "QuarkusApplication";
  public static final String PLURAL = "qapps";
  public static final String NAME = PLURAL + "." + GROUP;
}
