import { SqliteMetadataService } from "./sqlite-metadata.service";


test('adds 1 + 2 to equal 3', async () => {

    const sut = new SqliteMetadataService('../dist/preprocessed/reports.db');
    // const sut = new SqliteMetadataService('reports.db');
    const res = await sut.findFor('*');
    console.log(res);
    expect(res).toHaveLength(1);
});