export type ReportId = string;

export interface Report {
  id: ReportId;
  title: string;
  description?: string;
  version: string;
  complex: boolean;
  outputFormats: string[];
}

export interface Parameter {
  name: ReportId;
  type: string;
  label?: string;
  description?: string;
}
