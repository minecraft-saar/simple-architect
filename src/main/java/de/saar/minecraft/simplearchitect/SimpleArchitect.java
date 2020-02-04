package de.saar.minecraft.simplearchitect;

import com.google.common.collect.Iterables;
import de.saar.coli.minecraft.MinecraftRealizer;
import de.saar.coli.minecraft.relationextractor.Block;
import de.saar.coli.minecraft.relationextractor.MinecraftObject;
import de.saar.coli.minecraft.relationextractor.Relation;
import de.saar.coli.minecraft.relationextractor.UniqueBlock;
import de.saar.minecraft.architect.Architect;
import de.saar.minecraft.shared.BlockDestroyedMessage;
import de.saar.minecraft.shared.BlockPlacedMessage;
import de.saar.minecraft.shared.StatusMessage;
import de.saar.minecraft.shared.TextMessage;
import de.saar.minecraft.shared.WorldSelectMessage;
import de.up.ling.irtg.algebra.ParserException;
import io.grpc.stub.StreamObserver;
import umd.cs.shop.JSJshop;
import umd.cs.shop.JSPlan;
import umd.cs.shop.JSTaskAtom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SimpleArchitect implements Architect {
    Block lastBlock;
    private List<Block> plan;
    private Set<MinecraftObject> world;
    private int waitTime;
    private MinecraftRealizer realizer;
    private AtomicInteger numInstructions = new AtomicInteger(0);
    private AtomicLong lastUpdate = new AtomicLong(0);
    private String currentInstruction;
  
    public SimpleArchitect(int waitTime) {
        int mctsruns = 10000; //number of runs the planer tries to do
        int timeout = 10000; //time the planer runs in ms
        JSJshop planer = new JSJshop();
        var initialworld = SimpleArchitect.class.getResourceAsStream("/de/saar/minecraft/worlds/artengis.csv");
        var domain = SimpleArchitect.class.getResourceAsStream("/de/saar/minecraft/domains/house-block.lisp");
        // String bridge = "build-bridge x y z width length height"
        // for simple bridge height is height of banister, for bridge fancy height of arch
        // String house = "build-house x y z width length height"
        String problem = "build-house 3 2 3 4 4 3";
        JSPlan jshopPlan = planer.nlgSearch(mctsruns,timeout, initialworld, problem, domain);


        this.waitTime = waitTime;
        this.realizer = MinecraftRealizer.createRealizer();
        this.plan = new ArrayList<>();
        world = new HashSet<>();
        world.add(new UniqueBlock("blue", 3, 3, 3));
        transformPlan(jshopPlan);
        currentInstruction = generateResponse();
    }

    public SimpleArchitect() {
        this(1000);
    }

    @Override
    public void initialize(WorldSelectMessage message) {
      
    }

    public void transformPlan(JSPlan jshopPlan){
        JSTaskAtom t;
        for (short i = 0; i < jshopPlan.size(); i++) {
            t = (JSTaskAtom) jshopPlan.elementAt(i);
            String task = t.toStr().toString();
            String[] taskArray = task.split(" ");
            int x = (int) Double.parseDouble(taskArray[1]);
            int y = (int) Double.parseDouble(taskArray[2]);
            int z = (int) Double.parseDouble(taskArray[3]);
            this.plan.add(new Block(x,y,z));
        }
    }

    @Override
    public void handleBlockPlaced(BlockPlacedMessage request,
                                  StreamObserver<TextMessage> responseObserver) {
        int type = request.getType();
        int gameId = request.getGameId();
        int currNumInstructions = numInstructions.incrementAndGet();
        // spawn a thread for a long-running computation
        new Thread(() -> {
            synchronized (realizer) {
                String response = "";
                if (currNumInstructions < numInstructions.get()) {
                    // other instructions were planned after this one,
                    // our information is outdated, so skip this one
                    return;
                }
                lastUpdate.set(java.lang.System.currentTimeMillis());
                int x = request.getX();
                int y = request.getY();
                int z = request.getZ();
                if (plan.isEmpty()) {
                    response = "you are done, no more changes neeeded!";
                } else {
                    var currBlock = plan.get(0);
                    if (currBlock.xpos == x
                            // && currBlock.ypos == y
                            && currBlock.zpos == z) {
                        // make the block respond to "it"
                        world.add(currBlock);
                        lastBlock = currBlock;
                        plan.remove(0);
                        currentInstruction = generateResponse();
                        response = "Great! now " + currentInstruction;
                    } else {
                        response = String.format("you put a block on (%d, %d, %d) but we wanted a block on (%d, %d, %d)",
                                x, y, z,
                                currBlock.xpos, currBlock.ypos, currBlock.zpos);
                    }
                }
                assert response != "";
                TextMessage mText = TextMessage.newBuilder().setGameId(gameId).setText(response).build();
                // send the text message back to the client
                responseObserver.onNext(mText);
                responseObserver.onCompleted();
            }
        }).start();
    }

    @Override
    public void handleBlockDestroyed(BlockDestroyedMessage request, StreamObserver<TextMessage> responseObserver) {
        int gameId = request.getGameId();
        responseObserver.onNext(TextMessage.newBuilder().setGameId(gameId).setText("Please add this block again :-(").build());
        responseObserver.onCompleted();
    }

    @Override
    public void handleStatusInformation(StatusMessage request, StreamObserver<TextMessage> responseObserver) {
        // only re-send the current instruction after five seconds.
        if (lastUpdate.get() > java.lang.System.currentTimeMillis() + 5000) {
            return;
        }
        int x = request.getX();
        int gameId = request.getGameId();
        
        // spawn a thread for a long-running computation
        new Thread() {
            @Override
            public void run() {
                TextMessage mText = TextMessage.newBuilder().setGameId(gameId).setText(currentInstruction).build();
                // send the text message back to the client
                responseObserver.onNext(mText);
                responseObserver.onCompleted();
            }
        }.start();
    }

    @Override
    public String getArchitectInformation() {
        return "SimpleArchitect";
    }

    public String generateResponse() {
        var response = "";
        var target = plan.get(0);
        var relations = Relation.generateAllRelationsBetweeen(
                Iterables.concat(world,
                        org.eclipse.collections.impl.factory.Iterables.iList(target)
                )
        );
        if (lastBlock != null) {
            relations.add(new Relation("it", lastBlock));
        }
        try {
            realizer.setRelations(relations);
            response = realizer.generateStatement("put", target, "type");
        } catch (ParserException e) {
            e.printStackTrace();
        }
        return response;
    }
}
