
import { Group } from "../model/group";
import { Principal } from "../model/principal";
import { GroupsService } from "../port/groups.service";

export class PrincipalOnlyGroupsService implements GroupsService {
    public findFor(principal: Principal): Promise<ReadonlyArray<Group>> {
        return Promise.resolve([{ name: 'Stamm Waldreiter', id: 'X' }, { name: 'Stamm Inka', id: 'Y' }]);
    }
}