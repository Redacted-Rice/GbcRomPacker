package redactedrice.rompacker;


import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.SortedMap;
import java.util.TreeMap;

import redactedrice.gbcframework.RomConstants;
import redactedrice.gbcframework.addressing.AddressRange;
import redactedrice.gbcframework.addressing.AssignedAddresses;
import redactedrice.gbcframework.addressing.BankAddress;
import redactedrice.gbcframework.utils.RomUtils;

public class DataManager {
    // TODO: Add a config file/interface for blocks to consider not free as well?

    // BankId, bank object
    private SortedMap<Byte, AllocatableBank> freeSpace;
    private AssignedAddresses assignedAddresses;

    public DataManager() {
        freeSpace = new TreeMap<>();
        assignedAddresses = new AssignedAddresses();
    }

    public AssignedAddresses allocateBlocks(byte[] bytesToAllocateIn, Blocks blocks) {
        freeSpace.clear();
        assignedAddresses.clear();

        // Determine what space we have free
        determineAllFreeSpace(bytesToAllocateIn, blocks.getAllBlankedBlocks());

        // Assign fixed blocks first so the movable ones can reference them
        allocateFixedBlocks(blocks.getAllFixedBlocks());

        // Handle the fixed blocks
        List<MovableBlock> toAlloc = new LinkedList<>(blocks.getAllMovableBlocks());
        toAlloc.addAll(tryFixHybridBlocks(blocks.getAllHybridBlocks()));

        // Allocate space for the constrained blocks then the unconstrained ones
        if (tryToAssignBanks(toAlloc)) {
            // If we were successful, assign the addresses for each item and then return
            // a copy of the data
            assignAddressesInBanks();
            return new AssignedAddresses(assignedAddresses);
        }

        throw new IllegalArgumentException("Failed to assign address for the all the blocks!");
    }

    private void allocateFixedBlocks(List<FixedBlock> fixedBlocks) {
        // First assign them to their banks. This allows some optimization of sizes some
        for (FixedBlock block : fixedBlocks) {
            BankAddress address = block.getFixedAddress();
            block.assignBank(address.getBank(), assignedAddresses);
        }

        // Now go through and assign preliminary addresses and add them to the banks
        for (FixedBlock block : fixedBlocks) {
            BankAddress address = block.getFixedAddress();
            block.assignAddresses(address, assignedAddresses);
            if (!freeSpace.get(address.getBank()).addFixedBlock(block, assignedAddresses)) {
                throw new RuntimeException(String.format(
                        "There was not space from 0x%x to 0x%x in bank 0x%x for FixedBlock %s",
                        address.getAddressInBank(),
                        address.getAddressInBank() + block.getWorstCaseSize(assignedAddresses),
                        address.getBank(), block.getId()));
            }
        }
    }

    private List<MovableBlock> tryFixHybridBlocks(List<HybridBlock> hybridBlocks) {
        // For each fixed block, try first to fix it
        List<MovableBlock> unfixed = new LinkedList<>();

        // First treat them like fixed blocks

        // Assign them to their banks. This allows some optimization of sizes some
        for (HybridBlock block : hybridBlocks) {
            BankAddress address = block.getFixedBlock().getFixedAddress();
            block.getFixedBlock().assignBank(address.getBank(), assignedAddresses);
        }

        // Now go through and assign preliminary addresses and try to add them to the banks.
        // If they fail to add, then we treat them as movable blocks
        for (HybridBlock block : hybridBlocks) {
            BankAddress address = block.getFixedBlock().getFixedAddress();
            block.getFixedBlock().assignAddresses(address, assignedAddresses);
            if (!freeSpace.get(address.getBank()).addFixedBlock(block.getFixedBlock(),
                    assignedAddresses)) {
                // If we couldn't add it, then add it to the list of ones we couldn't fix and remove
                // the
                // assigned addresses
                unfixed.add(block.getMovableBlock());
                block.getFixedBlock().removeAddresses(assignedAddresses);
            }
        }

        return unfixed;
    }

    private boolean tryToAssignBanks(List<MovableBlock> toAlloc) {
        // Set/Reset each of the blocks working copy of the preferences
        for (MovableBlock block : toAlloc) {
            block.resetBankPreferences();
        }

        // Then recursively try to pack them
        return tryToAssignBanksRecursor(toAlloc);
    }

    private boolean tryToAssignBanksRecursor(List<MovableBlock> toAlloc) {
        // Assign each alloc to its next preferred/allowable bank
        if (!assignAllocationsToTheirNextBank(toAlloc)) {
            return false;
        }

        // Go through and remove any allocations that don't fit in the banks as
        // they are currently assigned
        List<MovableBlock> allocsThatDontFit = new LinkedList<>();
        removeExcessAllocsFromBanks(allocsThatDontFit);

        // If all allocs fit, we are done
        if (allocsThatDontFit.isEmpty()) {
            return true;
        }

        // Otherwise recurse again and see if the ones that didn't fit will fit in
        // their next bank (if they have one)
        return tryToAssignBanksRecursor(allocsThatDontFit);
    }

    private boolean assignAllocationsToTheirNextBank(List<MovableBlock> toAlloc) {
        for (MovableBlock alloc : toAlloc) {
            // Ran out of banks to try! Failed to allocate a block
            if (alloc.isUnattemptedAllowableBanksEmpty()) {
                return false;
            } else {
                byte nextBank = alloc.popNextUnattemptedAllowableBank();
                AllocatableBank bank = freeSpace.get(nextBank);
                if (bank == null) {
                    throw new RuntimeException(String.format(
                            "Popped next allowable bank (0x%x) for block " + alloc.getId()
                                    + " but failed to get a reference to the bank! This should never "
                                    + "happen if valid banks are given for the preferences",
                            nextBank));
                }
                bank.addMovableBlock(alloc);
                alloc.assignBank(bank.bank, assignedAddresses);
            }
        }

        return true;
    }

    private void removeExcessAllocsFromBanks(List<MovableBlock> allocsThatDontFit) {
        removeExcessAllocsFromBanksRecursor(allocsThatDontFit);
    }

    private boolean removeExcessAllocsFromBanksRecursor(List<MovableBlock> allocsThatDontFit) {
        // We have to track this separately from the list since the list passed in may
        // not be empty
        boolean foundAllocThatDoesntFit = false;

        // For each bank, pack and remove any excess
        List<MovableBlock> bankAllocsThatDontFit = new LinkedList<>();
        for (AllocatableBank bank : freeSpace.values()) {
            foundAllocThatDoesntFit =
                    bank.checkForAndRemoveExcessAllocs(bankAllocsThatDontFit, assignedAddresses)
                            || foundAllocThatDoesntFit;
            allocsThatDontFit.addAll(bankAllocsThatDontFit);
        }

        // If we went through all banks and didn't find any that no longer fit, then we
        // have found all of them
        if (!foundAllocThatDoesntFit) {
            return foundAllocThatDoesntFit;
        }

        // Otherwise recurse again
        return removeExcessAllocsFromBanksRecursor(allocsThatDontFit);
    }

    private void assignAddressesInBanks() {
        // For each bank, assign actual addresses
        for (AllocatableBank bank : freeSpace.values()) {
            bank.assignAddresses(assignedAddresses);
        }
    }

    // engine banks 0-8 + 9 & a
    // effect functions: b (overflow to a?)
    // data banks c
    // text banks d-19 + 1a & 1b
    // gfx 1d-3b, 20 engine related to gfx, + 1f, 2f,30, 3c
    // audio 3d & 3e
    // sfx 3f

    private void determineAllFreeSpace(byte[] rawBytes, List<AddressRange> spacesToConsiderFree) {
        freeSpace.clear();
        // Create a copy because we remove from it as we use them up when looking through the
        // individual banks
        LinkedList<AddressRange> spacesLeftToConsiderFree = new LinkedList<>(spacesToConsiderFree);

        byte numBanks = (byte) Math.ceil((double) rawBytes.length / RomConstants.BANK_SIZE);
        for (byte bank = 0; bank < numBanks; bank++) {
            // Insert map for this bank
            AllocatableBank bankSpace = new AllocatableBank(bank);
            freeSpace.put(bank, bankSpace);

            determineFreeSpaceInBank(rawBytes, bankSpace, spacesLeftToConsiderFree);
        }
    }

    private void determineFreeSpaceInBank(byte[] rawBytes, AllocatableBank bank,
            List<AddressRange> spacesToConsiderFree) {
        int[] bankBounds = RomUtils.getBankBounds(bank.bank);
        // Loop through the bank looking for empty space
        int address = bankBounds[0];
        while (address <= bankBounds[1]) {
            if (rawBytes[address] == (byte) 0xFF) {
                int spaceStart = address;
                while (++address < bankBounds[1] && rawBytes[address] == (byte) 0xFF);

                // If we found space, then save it to the map
                // We only save spaces that are at least x long to prevent finding locations that
                // are probably
                // actually images which can be 0xFF
                if (address - spaceStart > 40) {
                    bank.addSpace(spaceStart, address);
                }
            }
            address++;
        }

        // Now add all the spaces that go in this bank
        addSpacesToConsiderFree(bank, spacesToConsiderFree);

        // Finally sort and combine them to remove overlap and arbitrary breaks
        bank.sortAndCombineSpaces();
    }

    private void addSpacesToConsiderFree(AllocatableBank bank,
            List<AddressRange> spacesToConsiderFree) {
        int[] bankBounds = RomUtils.getBankBounds(bank.bank);

        ListIterator<AddressRange> arItr = spacesToConsiderFree.listIterator();
        while (arItr.hasNext()) {
            AddressRange ar = arItr.next();
            int stopInclusive = ar.getStopExclusive() - 1;

            // If this ar starts in this block
            if (ar.getStart() >= bankBounds[0] && ar.getStart() <= bankBounds[1]) {
                // and ends it it, remove it and add it to this bank
                if (stopInclusive >= bankBounds[0] && stopInclusive <= bankBounds[1]) {
                    bank.addSpace(ar.getStart(), ar.getStopExclusive());
                    arItr.remove();
                }
                // Otherwise cut it at this bank and add that portion to the bank and
                // the replace it with the remaining portion
                else {
                    bank.addSpace(ar.getStart(), bankBounds[1] + 1); // To make exclusive
                    arItr.set(new AddressRange(bankBounds[1] + 1, ar.getStopExclusive()));
                }
            }
            // otherwise if this ar stops in this block cut it at this bank and add that
            // portion to the bank and the replace it with the remaining portion
            else if (stopInclusive >= bankBounds[0] && stopInclusive <= bankBounds[1]) {
                bank.addSpace(bankBounds[0], ar.getStopExclusive());
                arItr.set(new AddressRange(ar.getStart(), bankBounds[0]));
            }
        }
    }
}
