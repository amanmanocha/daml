.. _module-ioutemplate-98694:

Module Iou_template
-------------------

Templates
^^^^^^^^^

.. _type-ioutemplate-iou-55222:

**template** `Iou <type-ioutemplate-iou-55222_>`_

  .. list-table::
     :widths: 15 10 30
     :header-rows: 1
  
     * - Field
       - Type
       - Description
     * - issuer
       - Party
       - 
     * - owner
       - Party
       - 
     * - currency
       - Text
       - only 3-letter symbols are allowed
     * - amount
       - Decimal
       - must be positive
     * - regulators
       - [Party]
       - ``regulators`` may observe any use of the ``Iou``
  
  + **Choice External:Archive**
    
  
  + **Choice Merge**
    
    merges two "compatible" ``Iou``s
    
    .. list-table::
       :widths: 15 10 30
       :header-rows: 1
    
       * - Field
         - Type
         - Description
       * - otherCid
         - ContractId `Iou <type-ioutemplate-iou-55222_>`_
         - Must have same owner, issuer, and currency. The regulators may differ, and are taken from the original ``Iou``.
  
  + **Choice Split**
    
    splits into two ``Iou``s with
    smaller amounts
    
    .. list-table::
       :widths: 15 10 30
       :header-rows: 1
    
       * - Field
         - Type
         - Description
       * - splitAmount
         - Decimal
         - must be between zero and original amount
  
  + **Choice Transfer**
    
    changes the owner
    
    .. list-table::
       :widths: 15 10 30
       :header-rows: 1
    
       * - Field
         - Type
         - Description
       * - owner\_
         - Party
         - 

Functions
^^^^^^^^^

.. _function-ioutemplate-main-13221:

`main <function-ioutemplate-main-13221_>`_
  : Scenario ()
  
  A single test scenario covering all functionality that ``Iou`` implements.
  This description contains a link(http://example.com), some bogus <inline html>,
  and words\_ with\_ underscore, to test damldoc capabilities.
