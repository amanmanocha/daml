-- Copyright (c) 2019 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

{-# LANGUAGE DerivingStrategies #-}

module DA.Daml.Doc.Render.Rst
  ( renderRst
  ) where

import DA.Daml.Doc.Types
import DA.Daml.Doc.Render.Monoid

import qualified Data.Text.Prettyprint.Doc as Pretty
import Data.Text.Prettyprint.Doc (Doc, defaultLayoutOptions, layoutPretty, pretty, (<+>))
import Data.Text.Prettyprint.Doc.Render.Text (renderStrict)

import Data.Char
import qualified Data.Text as T
import Data.List.Extra

import CMarkGFM

renderRst :: RenderEnv -> RenderOut -> [T.Text]
renderRst env = \case
    RenderSpaced chunks -> spaced (map (renderRst env) chunks)
    RenderModuleHeader title -> header "-" title
    RenderSectionHeader title -> header "^" title
    RenderBlock block -> indent (renderRst env block)
    RenderList items -> spaced (map (bullet . renderRst env) items)
    RenderRecordFields fields -> renderRstFields env fields
    RenderParagraph text -> [renderRstText env text]
    RenderDocs docText -> docTextToRst docText
    RenderAnchor anchor -> [".. _" <> unAnchor anchor <> ":"]

renderRstText :: RenderEnv -> RenderText -> T.Text
renderRstText env = \case
    RenderConcat ts -> mconcatMap (renderRstText env) ts
    RenderPlain text -> text
    RenderStrong text -> T.concat ["**", text, "**"]
    RenderLink anchor text ->
        case lookupAnchor env anchor of
            Nothing -> text
            Just _ -> T.concat ["`", text, " <", unAnchor anchor, "_>`_"]
    RenderDocsInline docText ->
        T.unwords (docTextToRst docText)

-- Utilities

-- | Put an extra newline in between chunks. Because Rst has support for definition
-- lists, we *sometimes* don't put a newline in between, in particular when the
-- next line is indented and looks like a type signature or definition. This
-- should affect the output very little either way (it's only spacing).
spaced :: [[T.Text]] -> [T.Text]
spaced = intercalate [""] . respace
  where
    respace = \case
        [line1] : (line2 : block) : xs
            | any (`T.isPrefixOf` line1) ["`", "**type**", "**template instance**"]
            , any (`T.isPrefixOf` line2) ["  :", "  ="] ->
                (line1 : line2 : block) : respace xs
        x : xs -> x : respace xs
        [] -> []

indent :: [T.Text] -> [T.Text]
indent = map ("  " <>)

bullet :: [T.Text] -> [T.Text]
bullet [] = []
bullet (x : xs) = ("+ " <> x) : indent xs

header :: T.Text -> T.Text -> [T.Text]
header headerChar title =
    [ title
    , T.replicate (T.length title) headerChar
    ]

renderRstFields :: RenderEnv -> [(RenderText, RenderText, RenderText)] -> [T.Text]
renderRstFields _ []  = mempty
renderRstFields env fields = concat
    [ [ ".. list-table::"
      , "   :widths: 15 10 30"
      , "   :header-rows: 1"
      , ""
      , "   * - Field"
      , "     - Type"
      , "     - Description" ]
    , fieldRows
    ]
  where
    fieldRows = concat
        [ [ "   * - " <> escapeTr_ (renderRstText env name)
          , "     - " <> renderRstText env ty
          , "     - " <> renderRstText env doc ]
        | (name, ty, doc) <- fields
        ]

-- TODO (MK) Handle doctest blocks. Currently the parse as nested blockquotes.
docTextToRst :: DocText -> [T.Text]
docTextToRst = T.lines . renderStrict . layoutPretty defaultLayoutOptions . render . commonmarkToNode opts exts . unDocText
  where
    opts = []
    exts = []
    headingSymbol :: Int -> Char
    headingSymbol i =
      case i of
        1 -> '#'
        2 -> '*'
        3 -> '='
        4 -> '-'
        5 -> '^'
        6 -> '"'
        _ -> '='
    render :: Node -> Doc ()
    render node@(Node _ ty ns) =
      case ty of
        DOCUMENT -> Pretty.align (Pretty.concatWith (\x y -> x <> Pretty.line <> Pretty.line <> y) (map render ns))

        PARAGRAPH -> Pretty.align (foldMap render ns)
        CODE_BLOCK _info t ->
          Pretty.align (Pretty.vsep [".. code-block:: daml", "", Pretty.indent 2 (pretty t)])
        LIST ListAttributes{..} -> Pretty.align (Pretty.vsep (zipWith (renderListItem listType) [1..] ns))

        EMPH -> Pretty.enclose "*" "*" (foldMap render ns)
        STRONG -> Pretty.enclose "**" "**" (foldMap render ns)

        HEADING i ->
          Pretty.align $
            Pretty.width (foldMap render ns) $ \n ->
            pretty (replicate n (headingSymbol i))

        TEXT t -> prettyRst t
        CODE t -> Pretty.enclose "``" "``" (pretty t)

        SOFTBREAK -> Pretty.line

        LINK url _text -> foldMap render ns <> Pretty.enclose "(" ")" (pretty url)
          -- Proper links in RST mean to render the content within backticks
          -- and trailing underscore, and then carry around the URL to generate
          -- a ref under the paragraph.
          -- Simple solution: slap the URL into the text to avoid introducing
          -- that ref-collecting state.

        HTML_INLINE txt -> prettyRst txt
          -- Treat alleged HTML as text (no support for inline html) to avoid
          -- introducing bad line breaks (which would lead to misaligned rst).

        _ -> pretty (nodeToCommonmark opts Nothing node)

    renderListItem :: ListType -> Int -> Node -> Doc ()
    renderListItem ty i (Node _ _ ns) =
      itemStart <+> Pretty.align (foldMap render ns)
      where
        itemStart = case ty of
          BULLET_LIST -> "*"
          ORDERED_LIST -> pretty (show i) <> "."

    -- escape trailing underscores (which means a link in Rst) from words.
    -- Loses the newline structure (unwords . ... . words), but which
    -- commonMarkToNode destroyed earlier at the call site here.
    prettyRst :: T.Text -> Doc ()
    prettyRst txt = pretty $ leadingWhite <> T.unwords (map escapeTr_ (T.words txt)) <> trailingWhite
      where trailingWhite = T.takeWhileEnd isSpace txt
            leadingWhite  = T.takeWhile isSpace txt

escapeTr_ :: T.Text -> T.Text
escapeTr_ w | T.null w        = w
            | T.last w == '_' = T.init w <> "\\_"
            | otherwise       = w
