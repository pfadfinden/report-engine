import { GroupsService } from '../../src/domain/port/groups.service';
import { Group } from '../../src/domain/model/group';
import { Principal } from '../../src/domain/model/principal';

// The e2e stack has no Hitobito to talk to, and the shared "members" report fixture (see
// ../../../test-fixtures) has no onlyForType restriction, so any single group is enough to drive
// the real report-selection UI end to end.
export const E2E_GROUP: Group = {
  id: 'e2e-group-1',
  name: 'E2E Test Group',
  type: 'Group::Bundesebene',
  parentId: null,
};

export class FakeGroupsService implements GroupsService {
  async findFor(_principal: Principal): Promise<ReadonlyArray<Group>> {
    return [E2E_GROUP];
  }
}
