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

public class SimpleArchitect implements Architect {
    Block lastBlock;
    private List<Block> plan;
    private Set<MinecraftObject> world;
    private int waitTime;
    private MinecraftRealizer realizer;
    private AtomicInteger numInstructions = new AtomicInteger(0);
  
    public SimpleArchitect(int waitTime) {
        int mctsruns = 10000; //number of runs the planer tries to do
        int timeout = 10000; //time the planer runs in ms
        JSJshop planer = new JSJshop();
        JSPlan jshopPlan = planer.nlgSearch(mctsruns,timeout);


        this.waitTime = waitTime;
        this.realizer = MinecraftRealizer.createRealizer();
        this.plan = new ArrayList<>();
        world = new HashSet<>();
        world.add(new UniqueBlock("blue", 3, 3, 3));
        transformPlan(jshopPlan);
        //for (int i=4; i<10; i++) {
        //    plan.add(new Block(i,3,3));
        //}
    }

    public SimpleArchitect() {
        this(1000);
    }

    @Override
    public void initialize() {
      
    }

    public void transformPlan(JSPlan jshopPlan){
        JSTaskAtom t;
        for (short i = 0; i < jshopPlan.size(); i++) {
            t = (JSTaskAtom) jshopPlan.elementAt(i);
            int x = (int) t.elementAt(2);
            int y = (int) t.elementAt(3);
            int z = (int) t.elementAt(4);
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
                        response = generateResponse();
                        // make the block respond to "it"
                        lastBlock = currBlock;
                        plan.remove(0);
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
        int x = request.getX();
        int gameId = request.getGameId();

        // spawn a thread for a long-running computation
        new Thread() {
            @Override
            public void run() {
                String text = "your x was " + x;
                TextMessage mText = TextMessage.newBuilder().setGameId(gameId).setText(text).build();

                // delay for a bit
                try {
                    Thread.sleep(waitTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

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

    private String generateResponse() {
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
            response = realizer.generateStatement("put", target, "");
        } catch (ParserException e) {
            e.printStackTrace();
        }
        return response;
    }
}
