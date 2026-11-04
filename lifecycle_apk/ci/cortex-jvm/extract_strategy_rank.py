"""Compile the production pure ranker separately from its Android execution-identity store."""
import sys
from pathlib import Path
source = Path(sys.argv[1]).read_text()
marker = '/**\n * V5.0.8153 §ONE_IDENTITY_END_TO_END'
assert source.count(marker) == 1
Path(sys.argv[2]).write_text(source.split(marker)[0])
