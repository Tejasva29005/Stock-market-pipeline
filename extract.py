from pathlib import Path
import pandas as pd

DATA_DIR = Path("data")
RAW_DIR = DATA_DIR / "raw"
EXTRACTED_DIR = DATA_DIR / "extracted"

RAW_DIR.mkdir(parents=True, exist_ok=True)
EXTRACTED_DIR.mkdir(parents=True, exist_ok=True)

INPUT_FOLDER = RAW_DIR / "C:\pipeline\FullDataCsv"
OUTPUT_FILE = EXTRACTED_DIR / "extracted_stock_data.csv"

CHUNK_SIZE = 100000


COLUMN_ALIASES = {
    "date": "Date",
    "datetime": "Date",
    "timestamp": "Date",
    "time": "Date",

    "symbol": "Symbol",
    "ticker": "Symbol",
    "stock": "Symbol",

    "open": "Open",
    "high": "High",
    "low": "Low",
    "close": "Close",
    "volume": "Volume",
}

REQUIRED_COLUMNS = ["Date", "Symbol", "Open", "High", "Low", "Close", "Volume"]
def standardize_columns(chunk: pd.DataFrame) -> pd.DataFrame:
    chunk.columns = [col.strip() for col in chunk.columns]

    rename_map = {}
    for col in chunk.columns:
        key = col.strip().lower()
        if key in COLUMN_ALIASES:
            rename_map[col] = COLUMN_ALIASES[key]

    chunk = chunk.rename(columns=rename_map)
    return chunk

def extract_from_multiple_csv(input_folder=INPUT_FOLDER, output_file=OUTPUT_FILE, chunksize=CHUNK_SIZE):
    csv_files = list(input_folder.glob("*.csv"))

    if not csv_files:
        print(f"No CSV files found in folder: {input_folder}")
        return

    first_write = True
    total_rows = 0
    total_files = 0

    
    if output_file.exists():
        output_file.unlink()

    for file in csv_files:
        try:
            print(f"\nReading file: {file.name}")

            for chunk in pd.read_csv(file, chunksize=chunksize):
                chunk = standardize_columns(chunk)

                
                if "Symbol" not in chunk.columns:
                    chunk["Symbol"] = file.stem

                
                available_cols = [col for col in REQUIRED_COLUMNS if col in chunk.columns]
                chunk = chunk[available_cols].copy()

                
                essential_cols = ["Date", "Open", "High", "Low", "Close", "Volume"]
                if not all(col in chunk.columns for col in essential_cols):
                    print(f"Skipping chunk in {file.name} because required columns are missing.")
                    print("Columns found:", chunk.columns.tolist())
                    continue

                
                if "Symbol" not in chunk.columns:
                    chunk["Symbol"] = file.stem

                
                chunk = chunk[REQUIRED_COLUMNS]

                chunk.to_csv(
                    output_file,
                    mode="w" if first_write else "a",
                    header=first_write,
                    index=False
                )

                total_rows += len(chunk)
                first_write = False

            total_files += 1
            print(f"Finished: {file.name}")

        except Exception as e:
            print(f"Error reading {file.name}: {e}")

    print("\nExtraction completed successfully.")
    print(f"Total files processed: {total_files}")
    print(f"Total rows extracted: {total_rows}")
    print(f"Combined extracted file saved to: {output_file}")


if __name__ == "__main__":
    extract_from_multiple_csv()