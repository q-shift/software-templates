package ${{ values.java_package_name }};

public class QuarkusApplicationSpec {
  private String coordinates;
  private String repositoryUrl;

  public QuarkusApplicationSpec() {
  }

  public QuarkusApplicationSpec(String coordinates, String repositoryUrl) {
    this.coordinates = coordinates;
    this.repositoryUrl = repositoryUrl;
  }

  public String getCoordinates() {
    return coordinates;
  }

  public void setCoordinates(String coordinates) {
    this.coordinates = coordinates;
  }

  public String getRepositoryUrl() {
    return repositoryUrl;
  }

  public void setRepositoryUrl(String repositoryUrl) {
    this.repositoryUrl = repositoryUrl;
  }

}
