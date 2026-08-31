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

export interface Principal {
  id: PrincipalId;
  name: string;
  roles: ReadonlyArray<PrincipalRole>;
}
