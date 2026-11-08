export type ReportId = string;

export interface Report {
  id: ReportId;
  title: string;
  description?: string;
  version: string;
  complex: boolean;
  outputFormats: string[];
  /**
   * Hitobito group types this report is allowed to run for (e.g.
   * "Group::Bundesebene"). Empty means the report is available for all
   * group types.
   */
  onlyForType: string[];
  /**
   * Hitobito role classes (e.g. "Group::Bundesgeschaeftsstelle::Bundesgeschaeftsfuehrung")
   * of which the user must hold at least one (compared against the principal's
   * role_class). Empty means the report is available regardless of role.
   */
  onlyWithRole: string[];
}

export interface Parameter {
  name: ReportId;
  type: string;
  label?: string;
  description?: string;
  /**
   * Fixed choices for a "select" parameter, in metadata.yaml order. Absent
   * for every other type: those are free input fields.
   */
  options?: ParameterOption[];
}

export interface ParameterOption {
  value: string;
  label: string;
}
