from pathlib import Path
from collections import deque
import pandas as pd
import numpy as np

DATA_DIR = Path("data")
EXTRACTED_DIR = DATA_DIR / "extracted"
PROCESSED_DIR = DATA_DIR / "processed"

EXTRACTED_DIR.mkdir(parents=True, exist_ok=True)
PROCESSED_DIR.mkdir(parents=True, exist_ok=True)

INPUT_FILE = EXTRACTED_DIR / "extracted_stock_data.csv"
OUTPUT_FILE = PROCESSED_DIR / "processed_stock_data.csv"

CHUNK_SIZE = 100000

REQUIRED_COLUMNS = ["Date", "Symbol", "Open", "High", "Low", "Close", "Volume"]


def clean_chunk(chunk: pd.DataFrame) -> pd.DataFrame:
    chunk.columns = [col.strip() for col in chunk.columns]

    missing_cols = [col for col in REQUIRED_COLUMNS if col not in chunk.columns]
    if missing_cols:
        raise ValueError(f"Missing required columns: {missing_cols}")

    chunk = chunk[REQUIRED_COLUMNS].copy()
    chunk = chunk.drop_duplicates()

    chunk["Date"] = pd.to_datetime(chunk["Date"], errors="coerce")

    numeric_cols = ["Open", "High", "Low", "Close", "Volume"]
    for col in numeric_cols:
        chunk[col] = pd.to_numeric(chunk[col], errors="coerce")

    chunk = chunk.dropna(subset=["Date", "Symbol", "Open", "High", "Low", "Close", "Volume"])

    chunk = chunk[
        (chunk["Open"] >= 0) &
        (chunk["High"] >= 0) &
        (chunk["Low"] >= 0) &
        (chunk["Close"] >= 0) &
        (chunk["Volume"] >= 0)
    ]

    chunk = chunk.sort_values(["Symbol", "Date"]).reset_index(drop=True)
    return chunk


def add_features_chunk(chunk: pd.DataFrame, state: dict) -> pd.DataFrame:
    chunk["Price_Change"] = chunk["Close"] - chunk["Open"]
    chunk["High_Low_Spread"] = chunk["High"] - chunk["Low"]

    daily_returns = []
    ma10_list = []
    ma50_list = []
    vol10_list = []

    for _, row in chunk.iterrows():
        symbol = row["Symbol"]
        close_price = row["Close"]

        if symbol not in state:
            state[symbol] = {
                "prev_close": None,
                "close_10": deque(maxlen=10),
                "close_50": deque(maxlen=50),
                "return_10": deque(maxlen=10),
            }

        symbol_state = state[symbol]
        prev_close = symbol_state["prev_close"]

        if prev_close is None or prev_close == 0:
            daily_return = np.nan
        else:
            daily_return = (close_price - prev_close) / prev_close

        daily_returns.append(daily_return)

        symbol_state["close_10"].append(close_price)
        symbol_state["close_50"].append(close_price)

        ma10_list.append(np.mean(symbol_state["close_10"]))
        ma50_list.append(np.mean(symbol_state["close_50"]))

        if not np.isnan(daily_return):
            symbol_state["return_10"].append(daily_return)

        if len(symbol_state["return_10"]) >= 2:
            vol10 = np.std(symbol_state["return_10"], ddof=1)
        else:
            vol10 = np.nan

        vol10_list.append(vol10)
        symbol_state["prev_close"] = close_price

    chunk["Daily_Return"] = daily_returns
    chunk["MA_10"] = ma10_list
    chunk["MA_50"] = ma50_list
    chunk["Volatility_10"] = vol10_list

    return chunk


def transform_large_csv(input_file=INPUT_FILE, output_file=OUTPUT_FILE, chunksize=CHUNK_SIZE):
    first_write = True
    total_rows = 0
    chunk_count = 0
    state = {}

    try:
        if output_file.exists():
            output_file.unlink()

        for chunk in pd.read_csv(input_file, chunksize=chunksize):
            chunk_count += 1
            print(f"Processing chunk {chunk_count}...")

            cleaned_chunk = clean_chunk(chunk)
            featured_chunk = add_features_chunk(cleaned_chunk, state)

            featured_chunk.to_csv(
                output_file,
                mode="w" if first_write else "a",
                header=first_write,
                index=False
            )

            total_rows += len(featured_chunk)
            first_write = False

            print(f"Saved chunk {chunk_count} with {len(featured_chunk)} rows")

        print("\nTransformation completed successfully.")
        print(f"Total rows transformed: {total_rows}")
        print(f"Processed file saved to: {output_file}")

    except Exception as e:
        print(f"Error during transformation: {e}")


if __name__ == "__main__":
    transform_large_csv()