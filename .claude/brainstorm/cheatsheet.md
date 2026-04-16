| Case | Original URL | Custom Alias          | Action                     |
|------|--------------|-----------------------|----------------------------|
| A    | Yes          | No (Auto-gen)         | Return old record (Re-use) |
| B    | Yes          | Yes (Same owner)      | Return old record (Re-use) |
| C    | Different    | Yes (Any owner)       | Error (Alias unique)       |
| D    | Yes          | Different (New alias) | Add new record             | 