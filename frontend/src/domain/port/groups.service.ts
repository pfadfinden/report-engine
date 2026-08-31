import { Group } from '../model/group';
import { Principal } from '../model/principal';

export interface GroupsService {
  findFor(principal: Principal): Promise<ReadonlyArray<Group>>;
}
