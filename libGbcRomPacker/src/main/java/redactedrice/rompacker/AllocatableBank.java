package redactedrice.rompacker;

import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import redactedrice.gbcframework.addressing.AddressRange;
import redactedrice.gbcframework.addressing.AssignedAddresses;
import redactedrice.gbcframework.addressing.BankAddress;
import redactedrice.gbcframework.utils.RomUtils;

public class AllocatableBank 
{
	byte bank;
	AddressRange bankRange;
	List<AddressRange> spaces;
	List<FixedBlock> fixedAllocations;
	// We don't use a set because we modify allocation and its bad practice to
	// do that for items in a set even if it should not impact the compare function
	List<MovableBlock> priortizedAllocations;
	
	public AllocatableBank(byte bank)
	{
		this.bank = bank;
		int[] bankBounds = RomUtils.getBankBounds(bank);
		// + 1 because it is exclusive
		bankRange = new AddressRange(bankBounds[0], bankBounds[1] + 1);
		spaces = new LinkedList<>();
		fixedAllocations = new LinkedList<>();
		priortizedAllocations = new LinkedList<>();
	}
	
	public void addSpace(int startAddress, int stopAddress)
	{
		addSpace(new AddressRange(startAddress, stopAddress));
	}
	
	public void addSpace(AddressRange space)
	{
		// If the space isn't entirely in this banks range, then throw
		if (!bankRange.contains(space))
		{
			throw new IllegalArgumentException("Passed space is not entirely in this bank (" + bank + ")!" +
					" bank addresses: " + Arrays.toString(RomUtils.getBankBounds(bank)) + " and space addresses: " +
					space.getStart() + ", " + space.getStopExclusive());
		}
		// shift it to be relative to this bank and add it
		spaces.add(space.shiftNew(-bankRange.getStart()));
	}

	// Manually remove a space. Removing for fixed blocks should be done
	// via the addToBank function
	public void removeAddressSpace(AddressRange range) 
	{		
		// If this space doesn't overlap with this bank, then there is nothing to do
		if (bankRange.overlaps(range))
		{
			// Convert it relative to this bank
			AddressRange bankRelative = range.shiftNew(-bankRange.getStart());
			
			// Then go through and remove the overlap potentially adding a new space in if the removed space is in the middle
			// of an existing space
			AddressRange space;
			Iterator<AddressRange> spaceItr = spaces.iterator();
			List<AddressRange> newRanges = new LinkedList<>();
			while (spaceItr.hasNext())
			{
				space = spaceItr.next();
				AddressRange otherSplit = space.removeOverlap(bankRelative);
				
				if (space.isEmpty())
				{
					spaceItr.remove();
				}
				else if (otherSplit != null)
				{
					// We don't need to add the split now since we already have checked the space it came from
					newRanges.add(otherSplit);
				}
			}
			
			// Add any new ranges that were created
			spaces.addAll(newRanges);
		}
	}
	
	public void sortAndCombineSpaces()
	{
		AddressRange.sortAndCombine(spaces);
	}
	
	public boolean addFixedBlock(FixedBlock fixedAlloc, AssignedAddresses assignedAddresses)
	{
		// Get the spaces left with the current blocks
		List<AddressRange> spacesLeft = getSpacesLeftRemovingFixedAllocs(assignedAddresses);
		
		// Now try to add this one
		boolean success = tryRemoveEntireContainingSpace(fixedAlloc, spacesLeft, assignedAddresses);

		if (success)
		{
			fixedAllocations.add(fixedAlloc);
		}
		return success;
	}
	
	private List<AddressRange> getSpacesCopy()
	{
		List<AddressRange> spacesLeft = new LinkedList<>();
		for (AddressRange range : spaces)
		{
			spacesLeft.add(new AddressRange(range));
		}
		return spacesLeft;
	}
	
	private List<AddressRange> getSpacesLeftRemovingFixedAllocs(AssignedAddresses assignedAddresses)
	{
		List<AddressRange> spacesLeft = getSpacesCopy();
		
		for (FixedBlock block : fixedAllocations)
		{
			if (!tryRemoveEntireContainingSpace(block, spacesLeft, assignedAddresses))
			{
                throw new RuntimeException(String.format("Desync error! There was no longer space in bank 0x%x for FixedBlock %s from 0x%x to 0x%x - "
                        + "only ReplacementBlocks do not need free space in the bank", bank, block.getId(), 
                        block.getFixedAddress().getAddressInBank(), 
                        block.getFixedAddress().getAddressInBank() + block.getWorstCaseSize(assignedAddresses)));
			}
		}
		AddressRange.sortAndCombine(spaces);
			
		// Getting here means we found space for every fixed alloc
		return spacesLeft;
	}
	
	private boolean tryRemoveEntireContainingSpace(FixedBlock block, List<AddressRange> spacesLeft, AssignedAddresses assignedAddresses)
	{
		AddressRange fixedRange = new AddressRange(block.getFixedAddress().getAddressInBank(), block.getFixedAddress().getAddressInBank() + block.getWorstCaseSize(assignedAddresses));
		AddressRange containingSpace = null;
		int spaceIndex;
		for (spaceIndex = 0; spaceIndex < spacesLeft.size(); spaceIndex++)
		{
			// If it is contained in the space, we found where it lives. We 
			// need to remove it from the space and potentially add a new
			// space to the list
			if (spacesLeft.get(spaceIndex).contains(fixedRange))
			{
				containingSpace = spacesLeft.get(spaceIndex);
				break;
			}
		}
		
		// If we didn't find any space that fits this, then we cannot put it here so we
		// abort
		if (containingSpace == null)
		{
			return false;
		}
		
		// Otherwise its good - we found a space that contains it as expected
		AddressRange splitRange = containingSpace.removeOverlap(fixedRange);
		if (containingSpace.isEmpty())
		{
			spacesLeft.remove(spaceIndex);
		}
		
		if (splitRange != null)
		{
			spacesLeft.add(splitRange);
		}
		
		return true;
	}
	
	public void addMovableBlock(MovableBlock alloc)
	{
		priortizedAllocations.add(alloc);
    }

	public boolean checkForAndRemoveExcessAllocs(List<MovableBlock> allocsThatDontFit, AssignedAddresses assignedAddresses)
	{
		// Clear the spaces and output var
		// Clear just the addresses but keep the banks since they still are
		// here so we can get a better idea of the size
		allocsThatDontFit.clear();
		
		// Ensure the allocations are sorted
		priortizedAllocations.sort(MovableBlock.PRIORITY_SORTER);
		
		return checkForAndRemoveExcessAllocsInCollection(priortizedAllocations.iterator(), assignedAddresses, allocsThatDontFit);
	}

	// TODO later: Probably can optimize packing into bank space some (i.e. leave most space, leave smallest space)
	private boolean checkForAndRemoveExcessAllocsInCollection(Iterator<MovableBlock> allocItr, AssignedAddresses assignedAddresses, List<MovableBlock> allocsThatDontFit)
	{
		boolean stable = false;
		boolean placed;
		MovableBlock alloc;
		int allocSize;
		List<AddressRange> spacesLeft;
		while (!stable)
		{
			// start assuming this time we are good
			stable = true;
		
			// Reassign the fixed block addresses in case it can shrink based on where the movable blocks are allocated
			for (FixedBlock block : fixedAllocations)
			{
				block.assignAddresses(block.getFixedAddress(), assignedAddresses);
			}
			
			// Now get the spaces that are left after the fixed block spaces are removed
			spacesLeft = getSpacesLeftRemovingFixedAllocs(assignedAddresses);
			
			// Loop over each movable block and see if it fits
			while (allocItr.hasNext())
			{
				// For each block, we go through each space and see if there is room until
				// we either find room or run out of spaces
				alloc = allocItr.next();
				placed = false;
				allocSize = alloc.getWorstCaseSize(assignedAddresses);
				for (AddressRange space : spacesLeft)
				{
					if (allocSize <= space.size())
					{
						space.shrink(allocSize);
						placed = true;
					}
				}
				
				if (!placed)
				{
					// We removed one - the list ins't stable now
					// We need another pass to see if anything else no longer
					// will fit
					stable = false;
					alloc.removeAddresses(assignedAddresses);
					allocsThatDontFit.add(alloc);
					allocItr.remove();
				}
			}
		}
		
		return !allocsThatDontFit.isEmpty();
	}

	public void assignAddresses(AssignedAddresses assignedAddresses) 
	{
		// Get the spaces that are left after the fixed block spaces are removed
		List<AddressRange> spacesLeft = getSpacesLeftRemovingFixedAllocs(assignedAddresses);

		boolean placed;
		int allocSize;
		
		for (MovableBlock block : priortizedAllocations)
		{
			placed = false;
			allocSize = block.getWorstCaseSize(assignedAddresses);
			for (AddressRange space : spacesLeft)
			{
				if (allocSize <= space.size())
				{
					block.assignAddresses(new BankAddress(bank, (short) space.getStart()), assignedAddresses);
					space.shrink(allocSize);
					placed = true;
					break;
				}
			}
			
			if (!placed)
			{
				throw new RuntimeException(String.format("Failed to assign a address to block " + block.getId() +
						" while assigning addresses for bank 0x%x. This should not occur if all banks are added then "
						+ "Allocated via DataManger!", bank));
			}
		}
	}

	public byte getBank() 
	{
		return bank;
	}
}
