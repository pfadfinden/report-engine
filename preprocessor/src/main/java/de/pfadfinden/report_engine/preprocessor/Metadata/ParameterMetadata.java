package de.pfadfinden.report_engine.preprocessor.Metadata;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.OrderColumn;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
public class ParameterMetadata {
  @EmbeddedId @JsonIgnore public ParameterId id;

  @JsonBackReference
  @ManyToOne
  @MapsId("reportId")
  @JoinColumn(name = "report_id")
  public ReportMetadata report;

  public String label = "";
  public String description = "";
  public String comment = "";
  public String type;

  /**
   * Fixed choices for a {@code type: select} parameter, in the order they're listed in
   * metadata.yaml (kept via the position column). Empty for every other type.
   */
  @ElementCollection
  @CollectionTable(
      name = "ParameterOption",
      joinColumns = {
        @JoinColumn(name = "report_id", referencedColumnName = "report_id"),
        @JoinColumn(name = "parameter_name", referencedColumnName = "name")
      })
  @OrderColumn(name = "position")
  public List<ParameterOption> options = new ArrayList<>();

  @Embeddable
  public static class ParameterId implements Serializable {
    @Column(name = "report_id")
    private String reportId;

    @Column(name = "name")
    private String name;

    public ParameterId() {}

    public ParameterId(String reportId, String name) {
      this.reportId = reportId;
      this.name = name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof ParameterId)) return false;
      ParameterId that = (ParameterId) o;
      return Objects.equals(reportId, that.reportId) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(reportId, name);
    }
  }

  public ParameterMetadata() {
    this.id = new ParameterId();
  }

  public void setReport(ReportMetadata report) {
    this.report = report;
    this.id.reportId = report.id;
  }

  @JsonProperty("name")
  public void setName(String name) {
    this.id.name = name;
  }

  @JsonProperty("name")
  public String name() {
    return id.name;
  }
}
