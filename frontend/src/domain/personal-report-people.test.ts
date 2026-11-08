import { personalReportPeopleFor } from './personal-report-people';

const base = { id: 'kc-1', name: 'Jane Doe', roles: [] };

test('is empty when the own person id is unknown', () => {
  expect(personalReportPeopleFor(base)).toEqual([]);
});

test('contains only the user themself by default', () => {
  expect(personalReportPeopleFor({ ...base, personId: '42' })).toEqual([{ id: '42', name: 'Jane Doe' }]);
});

test('lists the user first, followed by managed people, without duplicating the user', () => {
  const people = personalReportPeopleFor({
    ...base,
    personId: '42',
    managedPeople: [
      { id: '42', name: 'Jane Doe' },
      { id: '7', name: 'Kid Doe' },
    ],
  });
  expect(people.map((person) => person.id)).toEqual(['42', '7']);
});
