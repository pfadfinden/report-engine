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
}

export interface Parameter {
  name: ReportId;
  type: string;
  label?: string;
  description?: string;
}
