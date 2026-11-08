import { PersonalReportPerson, Principal } from './model/principal';

/**
 * The people a principal may request personal documents (Bescheinigungen, Auskünfte) for: the user themself first,
 * then everyone they manage in Hitobito (see Principal.managedPeople). Empty when the user's own
 * Hitobito person id is unknown, since the document is looked up by it.
 */
export function personalReportPeopleFor(principal: Principal): ReadonlyArray<PersonalReportPerson> {
  if (!principal.personId) {
    return [];
  }
  const self: PersonalReportPerson = { id: principal.personId, name: principal.name };
  const managed = (principal.managedPeople ?? []).filter((person) => person.id !== self.id);
  return [self, ...managed];
}
