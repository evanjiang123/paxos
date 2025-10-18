#!/bin/bash

#============================================================================
# Local Testing Script for Paxos TreasureIsland
# This script tests your Paxos implementation locally on localhost
#============================================================================

# Configuration
BASEDIR=/Users/evanjiang/Desktop/comp_512/p2-students/comp512p2
GROUP=26
GAMEID=game-$GROUP-test

# Test parameters (can be modified)
NUM_PLAYERS=2
MAX_MOVES=20
INTERVAL=300
RANDSEED=12345678

# Ports for local testing
PORT_BASE=8000

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

#============================================================================
# Helper Functions
#============================================================================

print_header() {
    echo ""
    echo -e "${BLUE}============================================================================${NC}"
    echo -e "${BLUE}  $1${NC}"
    echo -e "${BLUE}============================================================================${NC}"
    echo ""
}

print_success() {
    echo -e "${GREEN}✓ $1${NC}"
}

print_error() {
    echo -e "${RED}✗ $1${NC}"
}

print_info() {
    echo -e "${YELLOW}ℹ $1${NC}"
}

cleanup_processes() {
    print_info "Cleaning up any existing Java processes..."
    pkill -f "TreasureIslandAppAuto" 2>/dev/null
    sleep 2
}

check_logs_identical() {
    local log1=$1
    local log2=$2

    if [ ! -f "$log1" ] || [ ! -f "$log2" ]; then
        print_error "Log files not found: $log1 or $log2"
        return 1
    fi

    # Compare logs (skip first line which has player-specific info)
    if tail -n +2 "$log1" | diff -q - <(tail -n +2 "$log2") > /dev/null 2>&1; then
        return 0
    else
        return 1
    fi
}

#============================================================================
# Main Testing Functions
#============================================================================

test_basic_consensus() {
    print_header "Test 1: Basic Consensus (2 players, no failures)"

    export autotesthost=localhost
    export process1=${autotesthost}:$((PORT_BASE + 1))
    export process2=${autotesthost}:$((PORT_BASE + 2))

    export processgroup="$process1,$process2"

    print_info "Starting $NUM_PLAYERS players..."
    print_info "Process 1: $process1"
    print_info "Process 2: $process2"
    print_info "Max moves: $MAX_MOVES, Interval: ${INTERVAL}ms"

    cd $BASEDIR

    # Start player 1
    java comp512st.tests.TreasureIslandAppAuto \
        $process1 $processgroup $GAMEID $NUM_PLAYERS 1 $MAX_MOVES $INTERVAL ${RANDSEED}1 \
        > ${GAMEID}-1-display.log 2>&1 &

    # Start player 2
    java comp512st.tests.TreasureIslandAppAuto \
        $process2 $processgroup $GAMEID $NUM_PLAYERS 2 $MAX_MOVES $INTERVAL ${RANDSEED}2 \
        > ${GAMEID}-2-display.log 2>&1 &

    print_info "Waiting for test to complete..."
    wait

    print_info "Test completed. Checking results..."

    # Check if log files exist
    if [ -f "${GAMEID}-1.log" ] && [ -f "${GAMEID}-2.log" ]; then
        print_success "Log files generated"

        # Check if logs are identical
        if check_logs_identical "${GAMEID}-1.log" "${GAMEID}-2.log"; then
            print_success "Logs are identical - Total order maintained!"

            # Count moves
            local moves=$(tail -n +2 "${GAMEID}-1.log" | wc -l | tr -d ' ')
            print_info "Total moves agreed upon: $moves"

            return 0
        else
            print_error "Logs differ - Total order NOT maintained!"
            print_info "Showing differences:"
            diff <(tail -n +2 "${GAMEID}-1.log") <(tail -n +2 "${GAMEID}-2.log") | head -20
            return 1
        fi
    else
        print_error "Log files not generated properly"
        return 1
    fi
}

test_concurrent_proposals() {
    print_header "Test 2: Concurrent Proposals (short interval)"

    export autotesthost=localhost
    export process1=${autotesthost}:$((PORT_BASE + 1))
    export process2=${autotesthost}:$((PORT_BASE + 2))
    export processgroup="$process1,$process2"

    local test_gameid="${GAMEID}-concurrent"
    local test_moves=15
    local test_interval=50  # Very short interval for concurrent proposals

    print_info "Testing with interval=${test_interval}ms (high concurrency)"

    cd $BASEDIR

    java comp512st.tests.TreasureIslandAppAuto \
        $process1 $processgroup $test_gameid $NUM_PLAYERS 1 $test_moves $test_interval ${RANDSEED}1 \
        > ${test_gameid}-1-display.log 2>&1 &

    java comp512st.tests.TreasureIslandAppAuto \
        $process2 $processgroup $test_gameid $NUM_PLAYERS 2 $test_moves $test_interval ${RANDSEED}2 \
        > ${test_gameid}-2-display.log 2>&1 &

    wait

    if [ -f "${test_gameid}-1.log" ] && [ -f "${test_gameid}-2.log" ]; then
        if check_logs_identical "${test_gameid}-1.log" "${test_gameid}-2.log"; then
            print_success "Concurrent proposals handled correctly!"
            return 0
        else
            print_error "Failed to maintain total order under high concurrency"
            return 1
        fi
    else
        print_error "Test failed to complete"
        return 1
    fi
}

test_three_players() {
    print_header "Test 3: Three Players"

    export autotesthost=localhost
    export process1=${autotesthost}:$((PORT_BASE + 1))
    export process2=${autotesthost}:$((PORT_BASE + 2))
    export process3=${autotesthost}:$((PORT_BASE + 3))
    export processgroup="$process1,$process2,$process3"

    local test_gameid="${GAMEID}-3players"
    local test_moves=15
    local test_interval=200
    local num_players=3

    print_info "Starting 3 players..."

    cd $BASEDIR

    java comp512st.tests.TreasureIslandAppAuto \
        $process1 $processgroup $test_gameid $num_players 1 $test_moves $test_interval ${RANDSEED}1 \
        > ${test_gameid}-1-display.log 2>&1 &

    java comp512st.tests.TreasureIslandAppAuto \
        $process2 $processgroup $test_gameid $num_players 2 $test_moves $test_interval ${RANDSEED}2 \
        > ${test_gameid}-2-display.log 2>&1 &

    java comp512st.tests.TreasureIslandAppAuto \
        $process3 $processgroup $test_gameid $num_players 3 $test_moves $test_interval ${RANDSEED}3 \
        > ${test_gameid}-3-display.log 2>&1 &

    wait

    if [ -f "${test_gameid}-1.log" ] && [ -f "${test_gameid}-2.log" ] && [ -f "${test_gameid}-3.log" ]; then
        local all_match=true

        if ! check_logs_identical "${test_gameid}-1.log" "${test_gameid}-2.log"; then
            all_match=false
        fi

        if ! check_logs_identical "${test_gameid}-1.log" "${test_gameid}-3.log"; then
            all_match=false
        fi

        if [ "$all_match" = true ]; then
            print_success "All 3 players maintained total order!"
            return 0
        else
            print_error "Logs differ between players"
            return 1
        fi
    else
        print_error "Test failed to complete"
        return 1
    fi
}

test_with_failure() {
    print_header "Test 4: Fault Tolerance (with process failure)"

    export autotesthost=localhost
    export process1=${autotesthost}:$((PORT_BASE + 1))
    export process2=${autotesthost}:$((PORT_BASE + 2))
    export process3=${autotesthost}:$((PORT_BASE + 3))
    export processgroup="$process1,$process2,$process3"
    export failmode_2=AFTERBECOMINGLEADER  # Player 2 will fail

    local test_gameid="${GAMEID}-failure"
    local test_moves=20
    local test_interval=200
    local num_players=3

    print_info "Starting 3 players (player 2 will fail after becoming leader)..."

    cd $BASEDIR

    java comp512st.tests.TreasureIslandAppAuto \
        $process1 $processgroup $test_gameid $num_players 1 $test_moves $test_interval ${RANDSEED}1 \
        > ${test_gameid}-1-display.log 2>&1 &

    java comp512st.tests.TreasureIslandAppAuto \
        $process2 $processgroup $test_gameid $num_players 2 $test_moves $test_interval ${RANDSEED}2 AFTERBECOMINGLEADER \
        > ${test_gameid}-2-display.log 2>&1 &

    java comp512st.tests.TreasureIslandAppAuto \
        $process3 $processgroup $test_gameid $num_players 3 $test_moves $test_interval ${RANDSEED}3 \
        > ${test_gameid}-3-display.log 2>&1 &

    wait

    unset failmode_2

    print_info "Checking if remaining processes maintained consensus..."

    if [ -f "${test_gameid}-1.log" ] && [ -f "${test_gameid}-3.log" ]; then
        if check_logs_identical "${test_gameid}-1.log" "${test_gameid}-3.log"; then
            print_success "Remaining processes maintained total order despite failure!"

            # Check that player 2 failed early
            if [ -f "${test_gameid}-2.log" ]; then
                local moves_2=$(tail -n +2 "${test_gameid}-2.log" | wc -l | tr -d ' ')
                local moves_1=$(tail -n +2 "${test_gameid}-1.log" | wc -l | tr -d ' ')
                if [ $moves_2 -lt $moves_1 ]; then
                    print_success "Player 2 failed as expected (processed $moves_2 moves vs $moves_1)"
                fi
            fi
            return 0
        else
            print_error "Remaining processes did not maintain total order"
            return 1
        fi
    else
        print_error "Test failed to complete"
        return 1
    fi
}

show_summary() {
    print_header "Test Results Summary"

    echo "Test 1 (Basic Consensus):        ${test1_result}"
    echo "Test 2 (Concurrent Proposals):   ${test2_result}"
    echo "Test 3 (Three Players):          ${test3_result}"
    echo "Test 4 (Fault Tolerance):        ${test4_result}"
    echo ""

    local total_passed=$(echo "$test1_result $test2_result $test3_result $test4_result" | grep -o "PASS" | wc -l | tr -d ' ')

    if [ $total_passed -eq 4 ]; then
        print_success "All tests passed! ($total_passed/4)"
    else
        print_error "Some tests failed ($total_passed/4 passed)"
    fi

    echo ""
    print_info "Log files are in: $BASEDIR"
    print_info "Island logs: ${GAMEID}-*.log"
    print_info "Display logs: ${GAMEID}-*-display.log"
    print_info "Process logs: ${GAMEID}-*-processinfo-*.log"
}

#============================================================================
# Main Execution
#============================================================================

print_header "Paxos TreasureIsland Local Testing Suite"

print_info "Configuration:"
echo "  BASEDIR:     $BASEDIR"
echo "  GROUP:       $GROUP"
echo "  CLASSPATH:   $BASEDIR/comp512p2.jar:$BASEDIR"
echo ""

# Set classpath
export CLASSPATH=$BASEDIR/comp512p2.jar:$BASEDIR

# Verify setup
if [ ! -f "$BASEDIR/comp512p2.jar" ]; then
    print_error "comp512p2.jar not found at $BASEDIR"
    exit 1
fi

if [ ! -d "$BASEDIR/comp512st" ]; then
    print_error "comp512st directory not found at $BASEDIR"
    exit 1
fi

print_success "Setup verified"

# Clean up old processes
cleanup_processes

# Run tests
if test_basic_consensus; then
    test1_result="${GREEN}PASS${NC}"
else
    test1_result="${RED}FAIL${NC}"
fi

sleep 2
cleanup_processes

if test_concurrent_proposals; then
    test2_result="${GREEN}PASS${NC}"
else
    test2_result="${RED}FAIL${NC}"
fi

sleep 2
cleanup_processes

if test_three_players; then
    test3_result="${GREEN}PASS${NC}"
else
    test3_result="${RED}FAIL${NC}"
fi

sleep 2
cleanup_processes

if test_with_failure; then
    test4_result="${GREEN}PASS${NC}"
else
    test4_result="${RED}FAIL${NC}"
fi

cleanup_processes

# Show summary
show_summary

print_info "Testing complete!"
