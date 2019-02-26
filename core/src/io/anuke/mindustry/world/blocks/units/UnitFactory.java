package io.anuke.mindustry.world.blocks.units;

import io.anuke.annotations.Annotations.Loc;
import io.anuke.annotations.Annotations.Remote;
import io.anuke.arc.Core;
import io.anuke.arc.collection.EnumSet;
import io.anuke.arc.graphics.g2d.Draw;
import io.anuke.arc.graphics.g2d.Lines;
import io.anuke.arc.graphics.g2d.TextureRegion;
import io.anuke.arc.math.Mathf;
import io.anuke.mindustry.Vars;
import io.anuke.mindustry.content.Fx;
import io.anuke.mindustry.entities.Effects;
import io.anuke.mindustry.entities.type.BaseUnit;
import io.anuke.mindustry.entities.type.TileEntity;
import io.anuke.mindustry.entities.type.Unit;
import io.anuke.mindustry.gen.Call;
import io.anuke.mindustry.graphics.Pal;
import io.anuke.mindustry.graphics.Shaders;
import io.anuke.mindustry.net.Net;
import io.anuke.mindustry.type.Item;
import io.anuke.mindustry.type.ItemStack;
import io.anuke.mindustry.type.UnitType;
import io.anuke.mindustry.ui.Bar;
import io.anuke.mindustry.world.Block;
import io.anuke.mindustry.world.Tile;
import io.anuke.mindustry.world.consumers.ConsumeItems;
import io.anuke.mindustry.world.meta.BlockFlag;
import io.anuke.mindustry.world.meta.BlockStat;
import io.anuke.mindustry.world.meta.StatUnit;
import io.anuke.mindustry.world.modules.ItemModule;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

public class UnitFactory extends Block{
    protected float gracePeriodMultiplier = 15f;
    protected float speedupTime = 60f * 60f * 20;
    protected float maxSpeedup = 2f;

    protected UnitType type;
    protected float produceTime = 1000f;
    protected float launchVelocity = 0f;
    protected TextureRegion topRegion;
    protected int maxSpawn = 2;

    public UnitFactory(String name){
        super(name);
        update = true;
        hasPower = true;
        hasItems = true;
        solid = false;
        flags = EnumSet.of(BlockFlag.producer, BlockFlag.target);

        consumes.require(ConsumeItems.class);
    }

    @Remote(called = Loc.server)
    public static void onUnitFactorySpawn(Tile tile){
        if(!(tile.entity instanceof UnitFactoryEntity) || !(tile.block() instanceof UnitFactory)) return;

        UnitFactoryEntity entity = tile.entity();
        UnitFactory factory = (UnitFactory) tile.block();

        entity.buildTime = 0f;
        entity.spawned ++;

        Effects.shake(2f, 3f, entity);
        Effects.effect(Fx.producesmoke, tile.drawx(), tile.drawy());

        if(!Net.client()){
            BaseUnit unit = factory.type.create(tile.getTeam());
            unit.setSpawner(tile);
            unit.set(tile.drawx() + Mathf.range(4), tile.drawy() + Mathf.range(4));
            unit.add();
            unit.velocity().y = factory.launchVelocity;
        }
    }

    @Override
    public void load(){
        super.load();

        topRegion = Core.atlas.find(name + "-top");
    }

    @Override
    public void setBars(){
        super.setBars();
        bars.add("progress", entity -> new Bar("blocks.progress", Pal.ammo, () -> ((UnitFactoryEntity)entity).buildTime / produceTime));
    }

    @Override
    public boolean outputsItems(){
        return false;
    }

    @Override
    public void setStats(){
        super.setStats();

        stats.add(BlockStat.craftSpeed, produceTime / 60f, StatUnit.seconds);
    }

    @Override
    public void unitRemoved(Tile tile, Unit unit){
        UnitFactoryEntity entity = tile.entity();
        entity.spawned --;
    }

    @Override
    public TextureRegion[] generateIcons(){
        return new TextureRegion[]{Core.atlas.find(name), Core.atlas.find(name + "-top")};
    }

    @Override
    public void draw(Tile tile){
        UnitFactoryEntity entity = tile.entity();
        TextureRegion region = type.iconRegion;

        Draw.rect(name, tile.drawx(), tile.drawy());

        Shaders.build.region = region;
        Shaders.build.progress = entity.buildTime / produceTime;
        Shaders.build.color.set(Pal.accent);
        Shaders.build.color.a = entity.speedScl;
        Shaders.build.time = -entity.time / 10f;

        Draw.shader(Shaders.build);
        Draw.rect(region, tile.drawx(), tile.drawy());
        Draw.shader();

        Draw.color(Pal.accent);
        Draw.alpha(entity.speedScl);

        Lines.lineAngleCenter(
                tile.drawx() + Mathf.sin(entity.time, 6f, Vars.tilesize / 2f * size - 2f),
                tile.drawy(),
                90,
                size * Vars.tilesize - 4f);

        Draw.reset();

        Draw.rect(topRegion, tile.drawx(), tile.drawy());
    }

    @Override
    public void update(Tile tile){
        UnitFactoryEntity entity = tile.entity();

        entity.time += entity.delta() * entity.speedScl;

        if(entity.spawned >= maxSpawn){
            return;
        }

        if(tile.isEnemyCheat()){
            entity.warmup += entity.delta();
        }

        if(!tile.isEnemyCheat()){
            //player-made spawners have default behavior

            if(hasRequirements(entity.items, entity.buildTime / produceTime) && entity.cons.valid()){

                entity.buildTime += entity.delta() * entity.power.satisfaction;
                entity.speedScl = Mathf.lerpDelta(entity.speedScl, 1f, 0.05f);
            }else{
                entity.speedScl = Mathf.lerpDelta(entity.speedScl, 0f, 0.05f);
            }
            //check if grace period had passed
        }else if(entity.warmup > produceTime*gracePeriodMultiplier){
            float speedMultiplier = Math.min(0.1f + (entity.warmup - produceTime * gracePeriodMultiplier) / speedupTime, maxSpeedup);
            //otherwise, it's an enemy, cheat by not requiring resources
            entity.buildTime += entity.delta() * speedMultiplier;
            entity.speedScl = Mathf.lerpDelta(entity.speedScl, 1f, 0.05f);
        }else{
            entity.speedScl = Mathf.lerpDelta(entity.speedScl, 0f, 0.05f);
        }

        if(entity.buildTime >= produceTime){
            entity.buildTime = 0f;

            Call.onUnitFactorySpawn(tile);
            useContent(tile, type);

            for(ItemStack stack : consumes.items()){
                entity.items.remove(stack.item, stack.amount);
            }
        }
    }

    @Override
    public boolean acceptItem(Item item, Tile tile, Tile source){
        for(ItemStack stack : consumes.items()){
            if(item == stack.item && tile.entity.items.get(item) < stack.amount * 2){
                return true;
            }
        }
        return false;
    }

    @Override
    public int getMaximumAccepted(Tile tile, Item item){
        for(ItemStack stack : consumes.items()){
            if(item == stack.item){
                return stack.amount * 2;
            }
        }
        return 0;
    }

    @Override
    public TileEntity newEntity(){
        return new UnitFactoryEntity();
    }

    @Override
    public boolean canProduce(Tile tile){
        UnitFactoryEntity entity = tile.entity();
        return entity.spawned < maxSpawn;
    }

    protected boolean hasRequirements(ItemModule inv, float fraction){
        for(ItemStack stack : consumes.items()){
            if(!inv.has(stack.item, (int) (fraction * stack.amount))){
                return false;
            }
        }
        return true;
    }

    public static class UnitFactoryEntity extends TileEntity{
        public float buildTime;
        public float time;
        public float speedScl;
        public float warmup; //only for enemy spawners
        public int spawned;

        @Override
        public void write(DataOutput stream) throws IOException{
            stream.writeFloat(buildTime);
            stream.writeFloat(warmup);
            stream.writeInt(spawned);
        }

        @Override
        public void read(DataInput stream) throws IOException{
            buildTime = stream.readFloat();
            warmup = stream.readFloat();
            spawned = stream.readInt();
        }
    }
}
