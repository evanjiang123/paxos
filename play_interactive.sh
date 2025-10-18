#!/bin/bash

# Interactive play script - shows live UI updates
# Usage: ./play_interactive.sh <player_number>
#   Example: ./play_interactive.sh 1

if [ $# -ne 1 ]; then
    echo "Usage: $0 <player_number>"
    echo "  Example: $0 1"
    exit 1
fi

PLAYERNUM=$1
BASEDIR=/Users/evanjiang/Desktop/comp_512/p2-students/comp512p2
GROUP=26
GAMEID=game-$GROUP-interactive

# Configure for 2 players on localhost
export process1=localhost:8001
export process2=localhost:8002

# Build process group
export processgroup="$process1,$process2"
NUMPLAYERS=2

# Enable display updates (opposite of auto test!)
export UPDATEDISPLAY=true
export TIMONOCHROME=false  # Enable colors

# Set classpath
export CLASSPATH=$BASEDIR/comp512p2.jar:$BASEDIR

# Get my process based on player number
if [ $PLAYERNUM -eq 1 ]; then
    MYPROCESS=$process1
elif [ $PLAYERNUM -eq 2 ]; then
    MYPROCESS=$process2
else
    echo "Invalid player number. Use 1 or 2"
    exit 1
fi

echo "============================================================"
echo "  Interactive Treasure Island - Player $PLAYERNUM"
echo "============================================================"
echo "  Process: $MYPROCESS"
echo "  Group: $processgroup"
echo "  GameID: $GAMEID"
echo ""
echo "  Controls:"
echo "    Arrow keys: Move"
echo "    c: Claim treasure"
echo "    q: Quit"
echo "============================================================"
echo ""

cd $BASEDIR

# Run the interactive app
java comp512st.tiapp.TreasureIslandApp $MYPROCESS $processgroup $GAMEID $NUMPLAYERS $PLAYERNUM
