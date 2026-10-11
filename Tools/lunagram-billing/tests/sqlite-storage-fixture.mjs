// A real file-backed SQLite adapter with the minimal Cloudflare storage API shape.
// It is a local TEST fixture, never imported by the deployed worker.
import { DatabaseSync } from 'node:sqlite';

export function openSqliteStorageForTests(path) {
    const db = new DatabaseSync(path);
    db.exec('PRAGMA journal_mode = WAL; PRAGMA busy_timeout = 5000;');
    let active = false;
    return {
        sql: { exec(query, ...bindings) { return db.prepare(query).all(...bindings); } },
        transactionSync(callback) {
            if (active) throw new Error('nested fixture transaction');
            db.exec('BEGIN IMMEDIATE');
            active = true;
            try {
                const result = callback();
                if (result && typeof result.then === 'function') throw new Error('async transaction');
                db.exec('COMMIT');
                return result;
            } catch (error) {
                db.exec('ROLLBACK');
                throw error;
            } finally { active = false; }
        },
        close: () => db.close(),
    };
}
