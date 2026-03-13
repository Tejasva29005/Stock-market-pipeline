from pathlib import Path
import pandas as pd
import sqlite3
data_dir = Path("data")
processed_dir= data_dir / "processed"
database_dir = data_dir / "database"
database_dir.mkdir(parents=True, exist_ok=True)

INPUT_FILE = processed_dir / "processed_stock_data.csv"
DB_FILE = database_dir / "stock_data.db"

CHUNK_SIZE = 100000
def load_to_database(input_file=INPUT_FILE, db_file=DB_FILE, chunksize=CHUNK_SIZE):

    print("Connecting to database...")

    conn = sqlite3.connect(db_file)

    total_rows = 0
    chunk_number = 0

    try:
        for chunk in pd.read_csv(input_file, chunksize=chunksize):

            chunk_number += 1
            print(f"Loading chunk {chunk_number}...")

            chunk.to_sql(
                "stock_prices",
                conn,
                if_exists="append",
                index=False
            )

            total_rows += len(chunk)

        print("\nLoading completed successfully.")
        print(f"Total rows inserted: {total_rows}")
        print(f"Database saved at: {db_file}")

    except Exception as e:
        print(f"Error while loading data: {e}")

    finally:
        conn.close()
if __name__ == "__main__":
    load_to_database()