An architect performing offline planning and real-time NLG.

The behaviour is configured with a config file; a set of example configurations can be found in the configs folder.

The config file must be supplied as the first argument.
You can additionally modify the port (used for connection with the broker) via the second argument (which
takes precedence over the port configured in the config file).

## running the architect server

With gradle, use this to run the architect server:

`./gradlew run --args="my-config.yaml"`


## configuration

There are currently four different architects:
 - BLOCK: instructs block by block
 - MEDIUM: teaches each high-level concept once block by block and then uses them
 - HIGHLEVEL: Only instructs high-level objects (wall, floor, railing, ...)
 - adaptive: simulates all three above and picks the one with the lowest
   overall cost according to the generation model (i.e. sum of the cost of
   each instuction)

you can swith between these by setting the `instructionlevel`.

The Architect uses
[minecraft-nlg](https://github.com/minecraft-saar/minecraft-nlg) as
the sentence generator.  minecraft-nlg is based on a grammar (see Köhn
and Koller, 2019) and computes the optimal instruction based on the
grammar's weights. By default, these are nearly all the same, i.e. it
always generates the shortest instruction.  The weights can also be
learned from logs previous games, by using the
[weight-estimator](https://github.com/minecraft-saar/weight-estimator).
For this, one has to set
 - `useTrainedWeights: true`
 - `weightTrainingDatabase: "jdbc:mariadb://localhost:3306/SOMEDATABASE"`
   (where the database string is the one where the games are stored)
   
Then the weight estimator will run bootstrapping to generate sets of
grammar weights, read out the lower and higher percentile you
configured (`trainingSamplingLowerPercentile` and
`trainingSamplingUpperPercentile`) and set each weight to a random
sample from the uniform distribution between the lower and upper
percentile.  This is by design not the optimal set of weights as it
still enables exploration for further games.  The weights are resampled
for every new game.

Alternatively, one can use random weights.  Then the weights will be
randomized for every game on a uniform scale between 1 and 10.  Set
`randomizeWeights: true` for this.

When running experiments, you can set a secret word to be shown once
the requirements to get paid are fulfilled.  We use this together with
an external form which only continues after entering the secret word.
set `secretWord: MYSECRETWORD`.  This word is shown when the
instructions were completed or alternatively if a number of correct
blocks has been placed and a certain amount of time has passed. 
You can configure both with `timeoutMinutes: 10` and
`timeoutMinBlocks: 4`.

The `configs/` directory contains a selection of different
configurations.
