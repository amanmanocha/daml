-- Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0
{-# LANGUAGE OverloadedStrings   #-}
{-# LANGUAGE ApplicativeDo       #-}

-- | Main entry-point of the DAML compiler
module DA.Cli.Visual
  ( execVisual
  ) where


import qualified DA.Daml.LF.Ast as LF
import DA.Daml.LF.Ast.World as AST
import DA.Daml.LF.Reader
import qualified Data.NameMap as NM
import qualified Data.Set as Set
import qualified DA.Pretty as DAP
import qualified DA.Daml.LF.Proto3.Archive as Archive
import qualified Codec.Archive.Zip as ZIPArchive
import qualified Data.ByteString.Lazy as BSL
import Text.Dot
import qualified Data.ByteString as B
import Data.Generics.Uniplate.Data
import qualified Data.Map.Strict as Map
-- import Debug.Trace

data Action = ACreate (LF.Qualified LF.TypeConName)
            | AExercise (LF.Qualified LF.TypeConName) LF.ChoiceName deriving (Eq, Ord, Show )

startFromUpdate :: Set.Set (LF.Qualified LF.ExprValName) -> LF.World -> LF.Update -> Set.Set Action
startFromUpdate seen world update = case update of
    LF.UPure _ e -> startFromExpr seen world e
    LF.UBind (LF.Binding _ e1) e2 -> startFromExpr seen world e1 `Set.union` startFromExpr seen world e2
    LF.UCreate tpl e -> Set.singleton (ACreate tpl) `Set.union` startFromExpr seen world e
    LF.UExercise tpl chc e1 e2 e3 -> Set.singleton (AExercise tpl chc) `Set.union` startFromExpr seen world e1 `Set.union` maybe Set.empty (startFromExpr seen world) e2 `Set.union` startFromExpr seen world e3
    LF.UFetch _ ctIdEx -> startFromExpr seen world ctIdEx
    LF.UGetTime -> Set.empty
    LF.UEmbedExpr _ upEx -> startFromExpr seen world upEx
    LF.ULookupByKey _ -> Set.empty
    LF.UFetchByKey _ -> Set.empty

startFromExpr :: Set.Set (LF.Qualified LF.ExprValName) -> LF.World  -> LF.Expr -> Set.Set Action
startFromExpr seen world e = case e of
    LF.EVar _ -> Set.empty
    LF.EVal ref ->  case LF.lookupValue ref world of
        Right LF.DefValue{..}
            | ref `Set.member` seen  -> Set.empty
            | otherwise -> startFromExpr (Set.insert ref seen)  world dvalBody
        Left _ -> error "This should not happen"
    LF.EUpdate upd -> startFromUpdate seen world upd
    LF.ETmApp (LF.ETyApp (LF.EVal (LF.Qualified _ (LF.ModuleName ["DA","Internal","Template"]) (LF.ExprValName "fetch"))) _) _ -> Set.empty
    LF.ETmApp (LF.ETyApp (LF.EVal (LF.Qualified _  (LF.ModuleName ["DA","Internal","Template"])  (LF.ExprValName "archive"))) _) _ -> Set.empty
    expr -> Set.unions $ map (startFromExpr seen world) $ children expr

startFromChoice :: LF.World -> LF.TemplateChoice -> Set.Set Action
startFromChoice world chc = startFromExpr Set.empty world (LF.chcUpdate chc)

data ChoiceAndAction = ChoiceAndAction { choice :: LF.TemplateChoice ,actions :: Set.Set Action }
data TemplateChoiceAction = TemplateChoiceAction { template :: LF.Template ,choiceAndAction :: [ChoiceAndAction] }

templatePossibleUpdates :: LF.World -> LF.Template -> [ChoiceAndAction]
templatePossibleUpdates world tpl = map (\c -> (ChoiceAndAction c (startFromChoice world c))  ) (NM.toList (LF.tplChoices tpl))

moduleAndTemplates :: LF.World -> LF.Module -> [TemplateChoiceAction]
moduleAndTemplates world mod = retTypess
    where
        templates = NM.toList $ LF.moduleTemplates mod
        retTypess = map (\t-> TemplateChoiceAction t (templatePossibleUpdates world t ) ) templates

dalfBytesToPakage :: BSL.ByteString -> (LF.PackageId, LF.Package)
dalfBytesToPakage bytes = case Archive.decodeArchive $ BSL.toStrict bytes of
    Right a -> a
    Left err -> error (show err)

darToWorld :: ManifestData -> LF.Package -> LF.World
darToWorld manifest pkg = AST.initWorldSelf pkgs pkg
    where
        pkgs = map dalfBytesToPakage (dalfsContent manifest)

-- type LookupTemplate = (LF.Qualified LF.TypeConName) -> LF.Template

-- lookupTemplateT :: LF.World -> (LF.Qualified LF.TypeConName) -> LF.Template
-- lookupTemplateT world qualTemplate = case AST.lookupTemplate qualTemplate world of
--   Right tpl -> tpl
--   Left _ -> error("Template lookup failed")

-- templateInAction ::  LookupTemplate  -> Action -> LF.Template
-- templateInAction lookupTemplate (ACreate  qtpl) = lookupTemplate qtpl
-- templateInAction lookupTemplate (AExercise qtpl _ ) = lookupTemplate qtpl

-- srcLabel :: (LF.Template, [Action]) -> [(String, String)]
-- srcLabel (tc, _) = [("shape","none"), ("label",DAP.renderPretty $ LF.tplTypeCon tc) ]

-- templatePairs :: (LF.Template, Set.Set Action) -> (LF.Template , (LF.Template , [Action]))
-- templatePairs (tc, actions) = (tc , (tc,  Set.elems actions))

-- actionsForTemplate :: LookupTemplate -> (LF.Template, [Action]) -> [LF.Template]
-- actionsForTemplate lookupTemplate (_tplCon, actions) = map (templateInAction lookupTemplate) actions

-- This to be used to generate the node ids and use as look up table
choiceNameWithId :: [TemplateChoiceAction] -> Map.Map LF.ChoiceName NodeId
choiceNameWithId tplChcActions = Map.fromList $ zip allChoiceFromAction (map userNodeId [0..])
  where choiceActions =  concatMap choiceAndAction tplChcActions
        allChoiceFromAction = map (LF.chcName . choice) choiceActions


-- This flattening is not doing exhaustive, will be missing the create and archives. Probably will filter for 1st iteration
nodeIdForChoice ::  Map.Map LF.ChoiceName NodeId -> LF.ChoiceName -> NodeId
nodeIdForChoice lookUpdata chc = case Map.lookup chc lookUpdata of
  Just node -> node
  Nothing -> error("Template node lookup failed")

data SubGraph = SubGraph { nodes :: [(LF.ChoiceName ,NodeId)] ,clusterTemplate :: LF.Template }

constructSubgraphsWithLables :: Map.Map LF.ChoiceName NodeId -> TemplateChoiceAction -> SubGraph
constructSubgraphsWithLables lookupData TemplateChoiceAction {..} = SubGraph  nodes template
  where choicesInTemplete = map (LF.chcName . choice) choiceAndAction
        nodes = map (\chc -> (chc, (nodeIdForChoice lookupData chc)) ) choicesInTemplete

actionToChoice :: Action -> LF.ChoiceName
actionToChoice (ACreate _) = LF.ChoiceName "Create"
actionToChoice (AExercise _ chc) = chc

choiceActionToChoicePairs :: ChoiceAndAction -> [(LF.ChoiceName, LF.ChoiceName)]
choiceActionToChoicePairs ChoiceAndAction {..} = map (\ac -> (LF.chcName choice, (actionToChoice ac))) (Set.elems actions)

graphEdges :: Map.Map LF.ChoiceName NodeId -> [TemplateChoiceAction] -> [(NodeId, NodeId)]
graphEdges lookupData tplChcActions = map (\(chn1, chn2) -> ( (nodeIdForChoice lookupData chn1) ,(nodeIdForChoice lookupData chn2) )) choicePairsForTemplates
  where chcActionsFromAllTemplates = concatMap (choiceAndAction) tplChcActions
        choicePairsForTemplates = concatMap choiceActionToChoicePairs chcActionsFromAllTemplates


subGraphString :: SubGraph -> [String]
subGraphString SubGraph {..} = dots
  where dots = map (\(chc, node) -> showDot (userNode node [("label", DAP.renderPretty chc) , ("shape", "circle") ]) ) nodes


constructDotGraph :: [SubGraph] -> [(NodeId, NodeId)] -> String
constructDotGraph subgraphs edges = unlines graphLines
  where subgraphsLines = concatMap subGraphString subgraphs
        edgesLines = map (\(n1, n2) -> showDot (edge n1 n2  []) ) edges
        graphLines = subgraphsLines ++ edgesLines
-- moduleAndTemplates -> [TemplateChoiceAction]
-- nodes world  -> choiceNameWithId :: [TemplateChoiceAction] -> Map.Map LF.ChoiceName NodeId
-- list of subgraphs constructSubgraphsWithLables :: Map.Map LF.ChoiceName NodeId -> TemplateChoiceAction -> SubGraph
-- edges to be written - graphEdges :: Map.Map LF.ChoiceName NodeId -> [TemplateChoiceAction] -> [(NodeId, NodeId)]



-- | 'netlistGraph' generates a simple graph from a netlist.
-- The default implementation does the edeges other way round. The change is on # 143
-- netlistGraph' :: (Ord a)
--           => (b -> [(String,String)])   -- ^ Attributes for each node
--           -> (b -> [a])                 -- ^ Out edges leaving each node
--           -> [(a,b)]                    -- ^ The netlist
--           -> Dot ()
-- netlistGraph' attrFn outFn assocs = do
--     let nodes = Set.fromList [a | (a, _) <- assocs]
--     let outs = Set.fromList [o | (_, b) <- assocs, o <- outFn b]
--     nodeTab <- sequence
--                 [do nd <- node (attrFn b)
--                     return (a, nd)
--                 | (a, b) <- assocs]
--     otherTab <- sequence
--                [do nd <- node []
--                    return (o, nd)
--                 | o <- Set.toList outs, o `Set.notMember` nodes]
--     let fm = M.fromList (nodeTab ++ otherTab)
--     sequence_
--         [(fm M.! dst) .->. (fm M.! src) | (dst, b) <- assocs,
--         src <- outFn b]

execVisual :: FilePath -> Maybe FilePath -> IO ()
execVisual darFilePath _dotFilePath = do
    putStrLn "the thing is commented"
    darBytes <- B.readFile darFilePath
    let manifestData = manifestFromDar $ ZIPArchive.toArchive (BSL.fromStrict darBytes)
    (_, lfPkg) <- errorOnLeft "Cannot decode package" $ Archive.decodeArchive (BSL.toStrict (mainDalfContent manifestData) )
    let modules = NM.toList $ LF.packageModules lfPkg
        world = darToWorld manifestData lfPkg
        -- tplLookUp = lookupTemplateT world
        res = concatMap (moduleAndTemplates world) modules --  [TemplateChoiceAction]
        nodeWorld = choiceNameWithId res
        subgraphsinW = map (constructSubgraphsWithLables nodeWorld) res
        graphEdgesString  = graphEdges nodeWorld res
        strdot = constructDotGraph subgraphsinW graphEdgesString
    putStrLn strdot
        -- actionEdges = map templatePairs res  -- (LF.Template , (LF.Template , [Action]))
        -- dotString = showDot $ netlistGraph' srcLabel (actionsForTemplate tplLookUp)  actionEdges
    -- case dotFilePath of
    --     Just outDotFile -> writeFile outDotFile dotString
    --     Nothing -> putStrLn dotString


errorOnLeft :: Show a => String -> Either a b -> IO b
errorOnLeft desc = \case
  Left err -> ioError $ userError $ unlines [ desc, show err ]
  Right x  -> return x

