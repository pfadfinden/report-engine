export type GroupId = string;

export interface Group {
  id: GroupId;
  name: string;
  /** Hitobito group class, e.g. "Group::Bundesamt" */
  type: string;
  parentId: GroupId | null;
}
