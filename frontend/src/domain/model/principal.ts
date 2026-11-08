import { GroupId } from './group';

export type PrincipalId = string;

export interface PrincipalRole {
  groupId: GroupId;
  groupName: string;
  role: string;
  roleClass: string;
  roleName: string;
  permissions: ReadonlyArray<string>;
}

/** A person (Hitobito person id = Mitgliedsnummer) a principal may request personal documents for. */
export interface PersonalReportPerson {
  id: string;
  name: string;
}

export interface Principal {
  id: PrincipalId;
  /** Hitobito person id of the user themself; absent if Hitobito's userinfo did not provide it. */
  personId?: string;
  /**
   * People this user manages in Hitobito. Extension point: Hitobito does not expose its
   * Person#manageds relation via API/userinfo yet, so nothing populates this today.
   */
  managedPeople?: ReadonlyArray<PersonalReportPerson>;
  name: string;
  roles: ReadonlyArray<PrincipalRole>;
}
