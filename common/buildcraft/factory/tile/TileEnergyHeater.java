package buildcraft.factory.tile;

import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.EnumFacing;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidTankInfo;
import net.minecraftforge.fluids.IFluidHandler;

import buildcraft.BuildCraftFactory;
import buildcraft.api.recipes.BuildcraftRecipeRegistry;
import buildcraft.api.recipes.IComplexRefineryRecipeManager.IHeatableRecipe;
import buildcraft.api.tiles.IControllable;
import buildcraft.api.tiles.IDebuggable;
import buildcraft.api.tiles.IHasWork;
import buildcraft.api.transport.IPipeTile;
import buildcraft.core.lib.RFBattery;
import buildcraft.core.lib.block.BlockBuildCraftBase;
import buildcraft.core.lib.block.TileBuildCraft;
import buildcraft.core.lib.fluids.Tank;
import buildcraft.core.lib.fluids.TankManager;

import io.netty.buffer.ByteBuf;

public class TileEnergyHeater extends TileBuildCraft implements IFluidHandler, IHasWork, IControllable, IDebuggable {
    private final Tank in, out;
    private final TankManager<Tank> manager;
    private IHeatableRecipe currentRecipe;
    private int sleep = 0, lateSleep = 0;

    public TileEnergyHeater() {
        this.setBattery(new RFBattery(1000, 20, 0));
        in = new Tank("in", 1000, this);
        out = new Tank("out", 1000, this);
        manager = new TankManager<>(in, out);
        mode = Mode.On;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        manager.deserializeNBT(nbt.getCompoundTag("tanks"));
    }

    @Override
    public void writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag("tanks", manager.serializeNBT());
    }

    @Override
    public void readData(ByteBuf stream) {
        manager.readData(stream);
        sleep = stream.readInt();
    }

    @Override
    public void writeData(ByteBuf stream) {
        manager.writeData(stream);
        stream.writeInt(sleep);
    }

    @Override
    public void update() {
        super.update();

        if (worldObj.isRemote) return;

        craft();
        export();
    }

    private void craft() {
        checkRecipe();
        if (mode == Mode.On) {
            if (hasWork()) {
                if (sleep > 0) {
                    sleep--;
                    return;
                }
                heat(true);
            } else if (hasWork(false)) {
                if (lateSleep < 20) {
                    lateSleep++;
                    return;
                }
                heat(false);
                lateSleep = 0;
            }
        }
    }

    private void export() {
        if (out.getFluidAmount() <= 0) return;
        IBlockState state = worldObj.getBlockState(getPos());
        if (state == null || state.getBlock() != BuildCraftFactory.energyHeaterBlock) return;
        EnumFacing curFace = state.getValue(BlockBuildCraftBase.FACING_PROP);
        EnumFacing exportDir = curFace.rotateYCCW();
        TileEntity tile = worldObj.getTileEntity(getPos().offset(exportDir));
        if (!(tile instanceof IPipeTile)) return;
        if (!(tile instanceof IFluidHandler)) return;
        IFluidHandler fluid = (IFluidHandler) tile;
        if (!fluid.canFill(exportDir.getOpposite(), out.getFluidType())) return;
        FluidStack stack = out.drain(20, true);
        int filled = fluid.fill(exportDir.getOpposite(), stack, true);
        if (filled < stack.amount) {
            FluidStack back = stack.copy();
            back.amount -= filled;
            out.fill(back, true);
        }
    }

    private void checkRecipe() {
        if (currentRecipe == null) {
            currentRecipe = BuildcraftRecipeRegistry.complexRefinery.getHeatableRegistry().getRecipeForInput(in.getFluid());
            if (currentRecipe != null) {
                sleep = currentRecipe.ticks();
            }
            return;
        }
        if (!currentRecipe.in().equals(in.getFluid())) {
            currentRecipe = null;
        }
    }

    private void heat(boolean care) {
        int heatDiff = currentRecipe.heatTo() - currentRecipe.heatFrom();
        int required = heatDiff * BuildCraftFactory.rfPerHeatPerMB * currentRecipe.ticks() * Math.min(in.getFluidAmount(), currentRecipe.in().amount);
        if (getBattery().useEnergy(required, required, false) == required) {
            FluidStack stack = in.drain(currentRecipe.in().amount, true);
            if (stack.amount < currentRecipe.in().amount) {
                if (currentRecipe.out().amount != currentRecipe.in().amount || care) {
                    in.fill(stack, true);
                } else {
                    FluidStack altOut = currentRecipe.out().copy();
                    altOut.amount = stack.amount;
                    out.fill(altOut, true);
                }
            } else {
                out.fill(currentRecipe.out(), true);
                sleep = currentRecipe.ticks();
            }
        }
    }

    // IFluidHandler
    @Override
    public int fill(EnumFacing from, FluidStack resource, boolean doFill) {
        if (BuildcraftRecipeRegistry.complexRefinery.getHeatableRegistry().getRecipeForInput(resource) == null) return 0;
        return in.fill(resource, doFill);
    }

    @Override
    public FluidStack drain(EnumFacing from, FluidStack resource, boolean doDrain) {
        if (!canDrain(from, resource.getFluid())) return null;
        if (out.getFluid().equals(resource)) return out.drain(resource.amount, doDrain);
        return null;
    }

    @Override
    public FluidStack drain(EnumFacing from, int maxDrain, boolean doDrain) {
        return out.drain(maxDrain, doDrain);
    }

    @Override
    public boolean canFill(EnumFacing from, Fluid fluid) {
        return in.fill(new FluidStack(fluid, 1), false) == 1;
    }

    @Override
    public boolean canDrain(EnumFacing from, Fluid fluid) {
        return out.drain(1, false) != null;
    }

    @Override
    public FluidTankInfo[] getTankInfo(EnumFacing from) {
        return new FluidTankInfo[] { in.getInfo(), out.getInfo() };
    }

    // Misc Interfaces

    @Override
    public boolean acceptsControlMode(Mode mode) {
        return mode == Mode.On || mode == Mode.Off;
    }

    @Override
    public boolean hasWork() {
        return hasWork(true);
    }

    private boolean hasWork(boolean care) {
        if (currentRecipe == null) return false;
        boolean ret = !care || in.getFluidAmount() >= currentRecipe.in().amount;
        ret &= out.isEmpty() || out.getFluid().equals(currentRecipe.out());
        ret &= out.getCapacity() - out.getFluidAmount() >= currentRecipe.out().amount;
        return ret;
    }

    @Override
    public void getDebugInfo(List<String> left, List<String> right, EnumFacing side) {
        Tank[] tanks = { in, out };
        left.add("");
        left.add("Sleep = " + sleep);
        left.add("Input");
        left.add(" " + tanks[0].getFluidAmount() + "/" + tanks[0].getCapacity() + "mB");
        left.add(" " + (tanks[0].getFluid() == null ? "empty" : tanks[0].getFluidType().getLocalizedName(tanks[0].getFluid())));
        left.add("Output");
        left.add(" " + tanks[1].getFluidAmount() + "/" + tanks[1].getCapacity() + "mB");
        left.add(" " + (tanks[1].getFluid() == null ? "empty" : tanks[1].getFluidType().getLocalizedName(tanks[1].getFluid())));
    }
}