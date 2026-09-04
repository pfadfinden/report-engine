package de.pfadfinden.report_engine.preprocessor.Metadata;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import de.pfadfinden.report_engine.preprocessor.Adapter.JpaConverterJson;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import java.util.List;

@Entity
@Table(name = "Reports")
public class ReportMetadata {
  @Id public String id;
  public String title;
  public String description = "";
  public String sql = "";
  public Boolean complex = false;
  public String additionalParameterDescription;

  @Convert(converter = JpaConverterJson.class)
  public List<String> outputFormats;

  /**
   * Report is only allowed to be executed if the user has at least one of these roles for the
   * target unit.
   */
  @Convert(converter = JpaConverterJson.class)
  public List<String> onlyWithRole;

  /**
   * Report is only allowed to be executed if the selected unit's group type is one of these (e.g.
   * "Group::Bundesebene"). Empty or absent means the report is available for all group types.
   */
  @Convert(converter = JpaConverterJson.class)
  public List<String> onlyForType;

  @JsonManagedReference
  @OneToMany(cascade = CascadeType.ALL, mappedBy = "report")
  public List<VersionMetadata> versionHistory;

  @JsonManagedReference
  @OneToMany(cascade = CascadeType.ALL, mappedBy = "report")
  public List<ParameterMetadata> parameter;
}
