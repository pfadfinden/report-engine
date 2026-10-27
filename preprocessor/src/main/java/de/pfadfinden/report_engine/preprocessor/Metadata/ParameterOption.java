package de.pfadfinden.report_engine.preprocessor.Metadata;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * One fixed choice of a {@code type: select} parameter: {@code value} is what gets passed to the
 * report, {@code label} is what the user sees in the frontend's dropdown.
 */
@Embeddable
public class ParameterOption {
  @Column(name = "value")
  public String value;

  @Column(name = "label")
  public String label = "";

  public ParameterOption() {}

  public ParameterOption(String value, String label) {
    this.value = value;
    this.label = label;
  }
}
