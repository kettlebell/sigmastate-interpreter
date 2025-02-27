package sigmastate

import org.ergoplatform.SigmaConstants.ScriptCostLimit
import org.ergoplatform.validation.ValidationRules
import org.ergoplatform.{ErgoBox, ErgoLikeContext}
import scorex.crypto.authds.avltree.batch.Lookup
import scorex.crypto.authds.{ADDigest, ADKey}
import scorex.crypto.hash.Blake2b256
import sigmastate.Values._
import sigmastate.eval.Extensions._
import sigmastate.eval.Sized._
import sigmastate.eval._
import sigmastate.helpers.TestingHelpers._
import sigmastate.helpers.{ContextEnrichingTestProvingInterpreter, ErgoLikeTestInterpreter}
import sigmastate.interpreter.ContextExtension
import sigmastate.interpreter.Interpreter.{ScriptEnv, ScriptNameProp, emptyEnv}
import sigmastate.utxo.CostTable
import sigmastate.utxo.CostTable._
import special.sigma.{AvlTree, SigmaTestingData}
import sigmastate.lang.Terms.ValueOps
import sigmastate.lang.exceptions.CostLimitException

class CostingSpecification extends SigmaTestingData with CrossVersionProps {
  implicit lazy val IR = new TestingIRContext {
//    override val okPrintEvaluatedEntries = true
    substFromCostTable = false
  }
  lazy val interpreter = new ContextEnrichingTestProvingInterpreter
  lazy val pkA = interpreter.dlogSecrets(0).publicImage
  lazy val pkB = interpreter.dlogSecrets(1).publicImage

  val printCosts = true

  val (keysArr, _, _, avlProver) = sampleAvlProver
  val keys = Colls.fromItems(keysArr(0))
  val key1 = keys(0)
  val key2 = keyCollGen.sample.get
  avlProver.performOneOperation(Lookup(ADKey @@ key1.toArray))
  val digest = avlProver.digest.toColl
  val lookupProof = avlProver.generateProof().toColl
  val avlTreeData = AvlTreeData(ADDigest @@ digest.toArray, AvlTreeFlags.AllOperationsAllowed, 32, None)
  val avlTree: AvlTree = CAvlTree(avlTreeData)

  lazy val env: ScriptEnv = Map(
    ScriptNameProp -> s"filename_verify",
    "key1" -> key1,
    "key2" -> key2,
    "keys" -> keys,
    "lookupProof" -> lookupProof,
    "pkA" -> pkA,
    "pkB" -> pkB
  )

  val extension: ContextExtension = ContextExtension(Map(
    1.toByte -> IntConstant(1),
    2.toByte -> BooleanConstant(true),
    3.toByte -> BigIntConstant(BigInt("12345678901").bigInteger)
  ))
  val tokenId = Blake2b256("tokenA")

  class TestData {
    val headerFlags = ErgoTree.headerWithVersion(ergoTreeVersionInTests)
    val selfBox = createBox(0,
        ErgoTree.withoutSegregation(headerFlags, TrueSigmaProp),
        Seq(tokenId -> 10L),
        Map(ErgoBox.R4 -> ByteArrayConstant(Array[Byte](1, 2, 3)),
            ErgoBox.R5 -> IntConstant(3),
            ErgoBox.R6 -> AvlTreeConstant(avlTree)))
    val outBoxA = testBox(10, ErgoTree.fromSigmaBoolean(headerFlags, pkA), 0)
    val outBoxB = testBox(20, ErgoTree.fromSigmaBoolean(headerFlags, pkB), 0)
    val dataBox = createBox(1000,
      ErgoTree.withoutSegregation(headerFlags, TrueSigmaProp),
      Seq(tokenId1 -> 10L, tokenId2 -> 20L),
      Map(ErgoBox.R4 -> IntConstant(100), ErgoBox.R5 -> BooleanConstant(true)))
    val tx = createTransaction(IndexedSeq(dataBox), IndexedSeq(outBoxA, outBoxB))
    val context = new ErgoLikeContext(
      lastBlockUtxoRoot = header2.stateRoot.asInstanceOf[CAvlTree].treeData,
      headers = headers, preHeader = preHeader,
      dataBoxes = IndexedSeq(dataBox),
      boxesToSpend = IndexedSeq(selfBox),
      spendingTransaction = tx, selfIndex = 0, extension,
      ValidationRules.currentSettings, ScriptCostLimit.value,
      CostTable.interpreterInitCost, activatedVersionInTests
    ).withErgoTreeVersion(ergoTreeVersionInTests)

    def cost(script: String)(expCost: Int): Unit = {
      val ergoTree = compiler.compile(env, script)
      val res = interpreter.reduceToCrypto(context, env, ergoTree).get.cost
      if (printCosts)
        println(script + s" --> cost $res")
      res shouldBe ((expCost * CostTable.costFactorIncrease / CostTable.costFactorDecrease) + CostTable.interpreterInitCost).toLong
    }

    val ContextVarAccess = getVarCost + selectField  // `getVar(id)` + `.get`
    val RegisterAccess = accessRegister + selectField  // `getReg(id)` + `.get`
    val GTConstCost = comparisonCost + constCost
    val LengthGTConstCost = collLength + GTConstCost
    val LengthGTCost = collLength + comparisonCost  // can be used when constCost is already accumulated

    val OutputsCost = selectField + accessBox * tx.outputs.length
    val InputsCost = selectField + accessBox * context.boxesToSpend.length
    val DataInputsCost = selectField + accessBox * context.dataBoxes.length
    val HeadersCost = selectField
    val PreHeaderCost = selectField
    val AccessHeaderCost = selectField + collByIndex + constCost
  }

  property("basic (smoke) tests") {
    val d = new TestData; import d._
    cost("{ getVar[Boolean](2).get }")(ContextVarAccess)

    cost("{ getVar[Int](1).get > 1 }")(ContextVarAccess + GTConstCost)

    // accessing two context variables
    cost("{ getVar[Int](1).get > 1 && getVar[Boolean](2).get }")(ContextVarAccess * 2 + GTConstCost + logicCost)

    // the same var is used twice doesn't lead to double cost
    cost("{ getVar[Int](1).get + 1 > getVar[Int](1).get }")(ContextVarAccess + plusMinus + constCost + comparisonCost)

    // cost is accumulated along the expression tree
    cost("{ getVar[Int](1).get + 1 > getVar[Int](1).get && getVar[Boolean](2).get }")(
      ContextVarAccess * 2 + plusMinus + constCost + comparisonCost + logicCost)
  }

  property("logical op costs") {
    val d = new TestData; import d._
    cost("{ val cond = getVar[Boolean](2).get; cond && cond }")(ContextVarAccess + logicCost)
    cost("{ val cond = getVar[Boolean](2).get; cond || cond }")(ContextVarAccess + logicCost)
    cost("{ val cond = getVar[Boolean](2).get; cond || cond && true }")(ContextVarAccess + logicCost * 2 + constCost)
    cost("{ val cond = getVar[Boolean](2).get; cond || cond && true || cond }")(ContextVarAccess + logicCost * 3 + constCost)
    cost("{ val cond = getVar[Boolean](2).get; cond ^ cond && true ^ cond }")(ContextVarAccess + logicCost * 3 + constCost)
    cost("{ val cond = getVar[Boolean](2).get; allOf(Coll(cond, true, cond)) }")(ContextVarAccess + logicCost * 2 + constCost)
    cost("{ val cond = getVar[Boolean](2).get; anyOf(Coll(cond, true, cond)) }")(ContextVarAccess + logicCost * 2 + constCost)
    cost("{ val cond = getVar[Boolean](2).get; xorOf(Coll(cond, true, cond)) }") (ContextVarAccess + logicCost * 2 + constCost)
  }

  property("atLeast costs") {
    val d = new TestData; import d._
    cost("{ atLeast(2, Coll(pkA, pkB, pkB)) }")(
      concreteCollectionItemCost * 3 + collToColl + proveDlogEvalCost * 2 + logicCost + constCost)
  }

  property("allZK costs") {
    val d = new TestData; import d._
    cost("{ pkA && pkB }") (proveDlogEvalCost * 2 + sigmaAndCost * 2)
  }

  property("anyZK costs") {
    val d = new TestData; import d._
    cost("{ pkA || pkB }") (proveDlogEvalCost * 2 + sigmaOrCost * 2)
  }

  property("blake2b256 costs") {
    val d = new TestData; import d._
    cost("{ blake2b256(key1).size > 0 }") (constCost + hashPerKb + LengthGTConstCost)
  }

  property("sha256 costs") {
    val d = new TestData; import d._
    cost("{ sha256(key1).size > 0 }") (constCost + hashPerKb + LengthGTConstCost)
  }

  property("byteArrayToBigInt") {
    val d = new TestData; import d._
    cost("{ byteArrayToBigInt(Coll[Byte](1.toByte)) > 0 }")(
      constCost // byte const
        + collToColl // concrete collection
        + concreteCollectionItemCost * 1 // build from array cost
        + castOp + newBigIntPerItem + comparisonBigInt + constCost)
  }

  property("byteArrayToLong") {
    val d = new TestData; import d._
    cost("{ byteArrayToLong(Coll[Byte](1.toByte, 1.toByte, 1.toByte, 1.toByte, 1.toByte, 1.toByte, 1.toByte, 1.toByte)) > 0 }") (
      constCost // byte const
        + collToColl // concrete collection
        + concreteCollectionItemCost * 8 // build from array cost
        + castOp + GTConstCost)
  }

  property("longToByteArray") {
    val d = new TestData; import d._
    cost("{ longToByteArray(1L).size > 0 }") (constCost + castOp + LengthGTConstCost)
  }

  property("decodePoint and GroupElement.getEncoded") {
    val d = new TestData; import d._
    cost("{ decodePoint(groupGenerator.getEncoded) == groupGenerator }") (selectField + selectField + decodePointCost + comparisonCost)
  }

  property("GroupElement.negate") {
    val d = new TestData; import d._
    cost("{ groupGenerator.negate != groupGenerator }") (selectField + negateGroup + comparisonCost)
  }

  property("GroupElement.exp") {
    val d = new TestData; import d._
    cost("{ groupGenerator.exp(getVar[BigInt](3).get) == groupGenerator }") (selectField + expCost + ContextVarAccess + comparisonCost)
  }

  property("SELF box operations cost") {
    val d = new TestData; import d._
    cost("{ SELF.value > 0 }")(accessBox + extractCost + GTConstCost)
    cost("{ SELF.id.size > 0 }")(accessBox + extractCost + LengthGTConstCost)
    cost("{ SELF.tokens.size > 0 }")(accessBox + extractCost + LengthGTConstCost)
    cost("{ SELF.creationInfo._1 > 0 }")(accessBox + accessRegister + selectField + GTConstCost)
    cost("{ SELF.R5[Int].get > 0 }")(accessBox + RegisterAccess + GTConstCost)
  }

  property("Global operations cost") {
    val d = new TestData; import d._
    // TODO costing: related to https://github.com/ScorexFoundation/sigmastate-interpreter/issues/479
    // cost("{ groupGenerator.isIdentity > 0 }")(selectField + selectField + GTConstCost)

    val sizeOfArgs = Seq(sizeOf(key1), sizeOf(key1)).foldLeft(0L)(_ + _.dataSize)
    val xorCost = constCost + perKbCostOf(sizeOfArgs, hashPerKb / 2)
    cost("{ xor(key1, key1).size > 0 }")(xorCost + LengthGTConstCost)
  }

  property("Context operations cost") {
    val d = new TestData; import d._
    cost("{ HEIGHT > 0 }")(selectField + GTConstCost)
    cost("{ OUTPUTS.size > 0 }")(OutputsCost + LengthGTConstCost)
    cost("{ INPUTS.size > 0 }")(InputsCost + LengthGTConstCost)
    cost("{ CONTEXT.dataInputs.size > 0 }")(DataInputsCost + LengthGTConstCost)
    cost("{ LastBlockUtxoRootHash.isUpdateAllowed }")(selectField + selectField)
    cost("{ MinerPubkey.size > 0 }")(selectField + LengthGTConstCost)
    cost("{ CONTEXT.headers.size > 0 }")(HeadersCost + LengthGTConstCost)
    cost("{ CONTEXT.preHeader.height > 0 }")(PreHeaderCost + selectField + GTConstCost)
    cost("{ CONTEXT.selfBoxIndex > 0 }") (selectField + GTConstCost)
  }

  property("PreHeader operations cost") {
    val d = new TestData; import d._
    cost("{ CONTEXT.preHeader.version > 0 }")(PreHeaderCost + selectField + castOp + GTConstCost)
    cost("{ CONTEXT.preHeader.parentId.size > 0 }")(PreHeaderCost + selectField + LengthGTConstCost)
    cost("{ CONTEXT.preHeader.timestamp > 0L }")(PreHeaderCost + selectField + GTConstCost)
    cost("{ CONTEXT.preHeader.nBits > 0L }")(PreHeaderCost + selectField + GTConstCost)
    cost("{ CONTEXT.preHeader.height > 0 }")(PreHeaderCost + selectField + GTConstCost)

    cost("{ CONTEXT.preHeader.minerPk == groupGenerator }")(
      PreHeaderCost + selectField + comparisonCost + selectField)

    cost("{ CONTEXT.preHeader.votes.size > 0 }")(PreHeaderCost + selectField + LengthGTConstCost)
  }

  property("Header operations cost") {
    val d = new TestData; import d._
    val header = "CONTEXT.headers(0)"
    cost(s"{ $header.id.size > 0 }")(AccessHeaderCost + selectField + LengthGTCost)
    cost(s"{ $header.version > 0 }")(AccessHeaderCost + selectField + castOp + comparisonCost)
    cost(s"{ $header.parentId.size > 0 }")(AccessHeaderCost + selectField + LengthGTCost)
    cost(s"{ $header.ADProofsRoot.size > 0 }")(AccessHeaderCost + selectField + LengthGTCost)
    cost(s"{ $header.stateRoot.isUpdateAllowed }")(AccessHeaderCost + selectField + selectField)
    cost(s"{ $header.transactionsRoot.size > 0 }")(AccessHeaderCost + selectField + LengthGTCost)
    cost(s"{ $header.timestamp > 0L }")(AccessHeaderCost + selectField + GTConstCost)
    cost(s"{ $header.nBits > 0L }")(AccessHeaderCost + selectField + GTConstCost)
    cost(s"{ $header.height > 0 }")(AccessHeaderCost + selectField + comparisonCost)
    cost(s"{ $header.extensionRoot.size > 0 }")(AccessHeaderCost + selectField + LengthGTCost)

    cost(s"{ $header.minerPk == groupGenerator }")(AccessHeaderCost + selectField + comparisonCost + selectField)

    cost(s"{ $header.powOnetimePk == groupGenerator }")(AccessHeaderCost + selectField + comparisonCost + selectField)

    cost(s"{ $header.powNonce.size > 0 }")(AccessHeaderCost + selectField + LengthGTCost)

    cost(s"{ $header.powDistance > 0 }")(AccessHeaderCost + selectField + comparisonBigInt + constCost)
    cost(s"{ $header.votes.size > 0 }")(AccessHeaderCost + selectField + LengthGTCost)
  }

  val AccessRootHash = selectField
  def perKbCostOf(dataSize: Long, opCost: Int) = {
    ((dataSize / 1024L).toInt + 1) * opCost
  }

  property("AvlTree operations cost") {
    val d = new TestData; import d._
    val rootTree = "LastBlockUtxoRootHash"
//    cost(s"{ $rootTree.digest.size > 0 }")(AccessRootHash + selectField + LengthGTConstCost)
//    cost(s"{ $rootTree.enabledOperations > 0 }")(AccessRootHash + selectField + castOp + GTConstCost)
//    cost(s"{ $rootTree.keyLength > 0 }")(AccessRootHash + selectField + GTConstCost)
//    cost(s"{ $rootTree.isInsertAllowed }")(AccessRootHash + selectField)
//    cost(s"{ $rootTree.isUpdateAllowed }")(AccessRootHash + selectField)
//    cost(s"{ $rootTree.isRemoveAllowed }")(AccessRootHash + selectField)
//    cost(s"{ $rootTree.updateDigest($rootTree.digest) == $rootTree }") shouldBe
//      (AccessRootHash + selectField + newAvlTreeCost + comparisonCost /* for isConstantSize AvlTree type */)
//    cost(s"{ $rootTree.updateOperations(1.toByte) == $rootTree }") shouldBe
//      (AccessRootHash + newAvlTreeCost + comparisonCost + constCost)

    val AccessTree = accessBox + RegisterAccess
    val selfTree = "SELF.R6[AvlTree].get"
    val sizeOfArgs = Seq(sizeOf(avlTree), sizeOf(key1), sizeOf(lookupProof)).foldLeft(0L)(_ + _.dataSize)
    val containsCost = perKbCostOf(sizeOfArgs, avlTreeOp)

    cost(s"{ $selfTree.contains(key1, lookupProof) }")(AccessTree + containsCost + 2 * constCost)
    cost(s"{ $selfTree.get(key1, lookupProof).isDefined }")(AccessTree + containsCost + 2 * constCost + selectField)
    cost(s"{ $selfTree.getMany(keys, lookupProof).size > 0 }")(AccessTree + containsCost + 2 * constCost + LengthGTConstCost)
    cost(s"{ $rootTree.valueLengthOpt.isDefined }") (AccessRootHash + selectField + selectField)
    cost(s"{ $selfTree.update(Coll[(Coll[Byte], Coll[Byte])]((key1, key1)), lookupProof).isDefined }") (
      AccessTree +
        perKbCostOf(
          Seq(sizeOf(avlTree), sizeOf(key1), sizeOf(key1), sizeOf(lookupProof)).foldLeft(0L)(_ + _.dataSize),
          avlTreeOp
        )
        + concreteCollectionItemCost + collToColl + constCost * 2 + newPairValueCost + selectField)
    cost(s"{ $selfTree.remove(keys, lookupProof).isDefined }")(
      AccessTree +
        perKbCostOf(
          Seq(sizeOf(avlTree), sizeOf(key1), sizeOf(lookupProof)).foldLeft(0L)(_ + _.dataSize),
          avlTreeOp
        )
        + constCost * 2 + selectField)
//    cost(s"{ $selfTree.insert(Coll[(Coll[Byte], Coll[Byte])]((key2, key1)), lookupProof).isDefined }")
//      (AccessTree +
//        perKbCostOf(
//          Seq(sizeOf(avlTree), sizeOf(key2), sizeOf(key1), sizeOf(lookupProof)).foldLeft(0L)(_ + _.dataSize),
//          avlTreeOp
//        )
//        + concreteCollectionItemCost + collToColl + constCost * 3 + newPairValueCost + selectField)
  }

  property("Coll operations cost") {
    val d = new TestData; import d._
    val coll = "OUTPUTS"
    val nOutputs = tx.outputs.length
    val collBytes = "CONTEXT.headers(0).id"
    cost(s"{ $coll.filter({ (b: Box) => b.value > 1L }).size > 0 }")(
      selectField + lambdaCost +
        (accessBox + extractCost + constCost + comparisonCost + lambdaInvoke) * nOutputs + collToColl + LengthGTConstCost)

    cost(s"{ $coll.flatMap({ (b: Box) => b.propositionBytes }).size > 0 }")(
      lambdaCost + selectField +
          (accessBox + extractCost + lambdaInvoke) * nOutputs + collToColl + LengthGTConstCost)

    cost(s"{ $coll.zip(OUTPUTS).size > 0 }")(
      selectField + accessBox * tx.outputs.length +
        accessBox * nOutputs * 2 + collToColl + LengthGTConstCost)
    cost(s"{ $coll.map({ (b: Box) => b.value })(0) > 0 }")(
      lambdaCost + selectField +
        (accessBox + extractCost + lambdaInvoke) * nOutputs
        + collToColl + collByIndex + constCost + GTConstCost)
    cost(s"{ $coll.exists({ (b: Box) => b.value > 1L }) }") (
      lambdaCost + selectField +
        (accessBox + extractCost + constCost + comparisonCost + lambdaInvoke) * nOutputs + collToColl)

    cost(s"{ $coll.append(OUTPUTS).size > 0 }")(
      selectField + accessBox * tx.outputs.length +
      accessBox * tx.outputs.length * 2 + collToColl + LengthGTConstCost)

    cost(s"{ $coll.indices.size > 0 }")(
      selectField + accessBox * tx.outputs.length + selectField + LengthGTConstCost)
    cost(s"{ $collBytes.getOrElse(0, 1.toByte) == 0 }")(
      AccessHeaderCost + selectField + castOp + collByIndex + comparisonCost + constCost)
//    cost(s"{ $coll.fold(0L, { (acc: Long, b: Box) => acc + b.value }) > 0 }")(
//      selectField + constCost +
//        (extractCost + plusMinus + lambdaInvoke) * nOutputs + GTConstCost)
    cost(s"{ $coll.forall({ (b: Box) => b.value > 1L }) }")(
      lambdaCost + selectField +
        (accessBox + extractCost + GTConstCost + lambdaInvoke) * nOutputs + collToColl)
    cost(s"{ $coll.slice(0, 1).size > 0 }")(
      selectField + collToColl + accessBox * tx.outputs.length + LengthGTConstCost)

//    cost(s"{ $collBytes.patch(1, Coll(3.toByte), 1).size > 0 }")(
//      AccessHeaderCost + constCost * 3 + concreteCollectionItemCost + collToColl + collToColl + LengthGTConstCost)
    cost(s"{ $collBytes.updated(0, 1.toByte).size > 0 }")(
      AccessHeaderCost + selectField + collToColl + LengthGTConstCost)
//    cost(s"{ $collBytes.updateMany(Coll(0), Coll(1.toByte)).size > 0 }")
//      (AccessHeaderCost + collToColl + constCost * 2 + concreteCollectionItemCost + LengthGTConstCost)
  }

  property("Option operations cost") {
    val d = new TestData; import d._
    val opt = "SELF.R5[Int]"
    val accessOpt = accessBox + accessRegister
    cost(s"{ $opt.get > 0 }")(accessOpt + selectField + GTConstCost)
    cost(s"{ $opt.isDefined }")(accessOpt + selectField)
    cost(s"{ $opt.getOrElse(1) > 0 }")(accessOpt + selectField + constCost + GTConstCost)
    cost(s"{ $opt.filter({ (x: Int) => x > 0}).isDefined }")(
      accessOpt + OptionOp + lambdaCost + GTConstCost + selectField)
    cost(s"{ $opt.map({ (x: Int) => x + 1}).isDefined }")(
      accessOpt + OptionOp + lambdaCost + plusMinus + constCost + selectField)
  }

  property("TrueLeaf cost") {
    val d = new TestData; import d._
    cost("{ true }")(constCost)
  }

  property("ErgoTree with TrueLeaf costs") {
    val d = new TestData; import d._
    val tree = ErgoTree(16, IndexedSeq(TrueLeaf), BoolToSigmaProp(ConstantPlaceholder(0, SBoolean)))

    val pr = interpreter.prove(tree, context, fakeMessage).get
    val expressionCost =
      constCost +
      logicCost + // SigmaPropIsProven
      logicCost // BoolToSigmaProp

    val expectedCost = (expressionCost * CostTable.costFactorIncrease / CostTable.costFactorDecrease) +
      CostTable.interpreterInitCost + tree.complexity

    if (isActivatedVersion4) {
      pr.cost shouldBe expectedCost
    } else {
      pr.cost shouldBe 10185
    }


    val verifier = new ErgoLikeTestInterpreter
    val cost = verifier.verify(emptyEnv, tree, context, pr, fakeMessage).get._2

    if (isActivatedVersion4) {
      cost shouldBe expectedCost
    } else {
      cost shouldBe 10185
    }
  }

  property("ErgoTree with SigmaPropConstant costs") {
    val d = new TestData; import d._

    /** Helper method used in tests.
      * @param expectedCostV5 expected value of v5.0 cost if defined, otherwise should be
      *                       equal to `expectedCost`
      */
    def proveAndVerify(ctx: ErgoLikeContext, tree: ErgoTree, expectedCost: Long, expectedCostV5: Option[Long] = None) = {
      val pr = interpreter.prove(tree, ctx, fakeMessage).get

      if (isActivatedVersion4) {
        pr.cost shouldBe expectedCost
      } else {
        pr.cost shouldBe expectedCostV5.getOrElse(expectedCost)
      }

      val verifier = new ErgoLikeTestInterpreter
      val cost = verifier.verify(emptyEnv, tree, ctx, pr, fakeMessage).get._2

      if (isActivatedVersion4) {
        cost shouldBe expectedCost
      } else {
        cost shouldBe expectedCostV5.getOrElse(expectedCost)
      }
    }

    // simple trees containing SigmaPropConstant
    val tree1 = ErgoTree.fromSigmaBoolean(pkA) // without segregation
    val tree2 = ErgoTree.withSegregation(pkA)  // with segregation, have different `complexity`

    {
      val ctx = context.withInitCost(0)
      proveAndVerify(ctx, tree1, expectedCost = 10141, expectedCostV5 = Some(483))
      proveAndVerify(ctx, tree2, expectedCost = 10161, expectedCostV5 = Some(503))
    }

    {
      val ctx = context.withInitCost(10000)
      proveAndVerify(ctx, tree1, expectedCost = 20141, expectedCostV5 = Some(10483))
      proveAndVerify(ctx, tree2, expectedCost = 20161, expectedCostV5 = Some(10503))
    }

    {
      if (isActivatedVersion4) {
        val ctx = context.withInitCost(10000).withCostLimit(20000)
        assertExceptionThrown(
          proveAndVerify(ctx, tree1, expectedCost = 20141),
          exceptionLike[CostLimitException](
            "Estimated execution cost", "exceeds the limit")
        )
      } else {
        val ctx = context.withInitCost(10000).withCostLimit(10400)
        assertExceptionThrown(
          proveAndVerify(ctx, tree1, expectedCost = 20141, expectedCostV5 = Some(10483)),
          exceptionLike[CostLimitException](
            "Estimated execution cost", "exceeds the limit")
        )
      }
    }

    // more complex tree without Deserialize
    val tree3 = ErgoTree.fromProposition(compiler
      .compile(env, "{ sigmaProp(HEIGHT == 2) }")
      .asSigmaProp)
    
    proveAndVerify(context.withInitCost(0), tree3, expectedCost = 541, expectedCostV5 = Some(495))
    proveAndVerify(context.withInitCost(10000), tree3, expectedCost = 10541, expectedCostV5 = Some(10495))
  }

  property("laziness of AND, OR costs") {
    val d = new TestData; import d._
    cost("{ val cond = getVar[Boolean](2).get; !(!cond && (1 / 0 == 1)) }")(
      ContextVarAccess + constCost * 2 + logicCost * 3 + multiply + comparisonCost)
    cost("{ val cond = getVar[Boolean](2).get; (cond || (1 / 0 == 1)) }")(
      ContextVarAccess + constCost * 2 + logicCost + multiply + comparisonCost)
  }
}